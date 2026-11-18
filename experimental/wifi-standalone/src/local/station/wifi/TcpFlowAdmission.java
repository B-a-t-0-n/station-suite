package local.station.wifi;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import local.station.relay.SelectiveSocksGateway;
import local.station.vpnprobe.PacketMetadata;

/**
 * Admission for public IPv4 TCP only. Local service flows have a separate policy.
 * OwnerLookup must perform a complete, exact SYN_SENT tuple lookup, never a
 * destination-only or ESTABLISHED fallback. This is not a TCP stack or a defence
 * against a privileged process that can read the gateway capability.
 */
public final class TcpFlowAdmission {
    private static final int SYN=2, RST=4, ACK=16, FIN=1;
    private static final long UINT32=0xffffffffL;

    public interface OwnerLookup {
        OwnerObservation lookup(byte[] localAddress,int localPort,byte[] remoteAddress,int remotePort) throws Exception;
    }
    public static final class OwnerObservation {
        public final boolean complete,found,ambiguous;
        public final int uid;
        public OwnerObservation(boolean complete,boolean found,boolean ambiguous,int uid){
            this.complete=complete;this.found=found;this.ambiguous=ambiguous;this.uid=uid;
        }
    }
    public enum Decision {
        NONE, INVALID_PACKET, CLOCK_INVALID, CLOCK_REWIND, NO_FLOW, UNEXPECTED_TCP,
        CAPACITY, OWNER_LOOKUP_ERROR, OWNER_INCOMPLETE, OWNER_MISSING,
        OWNER_AMBIGUOUS, OWNER_DENIED, OWNER_NOT_ALLOWED, OWNER_ALLOWED,
        SYN_RETRANSMIT_CACHED, EXISTING_FLOW
    }
    public static final class Limits {
        public final int maxFlows;
        public final long idleMs,pendingMs,synOwnerCacheMs,maxLifetimeMs,finGraceMs,rstGraceMs;
        public Limits(int maxFlows,long idleMs,long pendingMs,long synOwnerCacheMs,
                long maxLifetimeMs,long finGraceMs,long rstGraceMs){
            if(maxFlows<1||maxFlows>4096||idleMs<1||idleMs>3600000
                    ||pendingMs<1||pendingMs>60000||synOwnerCacheMs<0||synOwnerCacheMs>1000
                    ||maxLifetimeMs<idleMs||maxLifetimeMs>86400000
                    ||finGraceMs<0||finGraceMs>10000||rstGraceMs<0||rstGraceMs>5000)
                throw new IllegalArgumentException("bounded_flow_limits_required");
            this.maxFlows=maxFlows;this.idleMs=idleMs;this.pendingMs=pendingMs;
            this.synOwnerCacheMs=synOwnerCacheMs;this.maxLifetimeMs=maxLifetimeMs;
            this.finGraceMs=finGraceMs;this.rstGraceMs=rstGraceMs;
        }
        public static Limits defaults(){return new Limits(128,600000,15000,250,28800000,5000,1000);}
    }
    /** Endpoint-free observations. A caller must not infer ownership from status alone. */
    public static final class Snapshot {
        public final int activeFlows,lastOwnerUid;
        public final long ownerLookups,ownerAllowed,denied,outboundPackets,inboundPackets,generations;
        public final Decision lastDecision,lastOwnerDecision;
        private Snapshot(TcpFlowAdmission p){
            activeFlows=p.flows.size();lastOwnerUid=p.lastOwnerUid;ownerLookups=p.ownerLookups;
            ownerAllowed=p.ownerAllowed;denied=p.denied;outboundPackets=p.outboundPackets;
            inboundPackets=p.inboundPackets;generations=p.generations;
            lastDecision=p.lastDecision;lastOwnerDecision=p.lastOwnerDecision;
        }
    }
    private final OwnerLookup owners;
    private final Set<Integer> allowed,deniedUids;
    private final Limits limits;
    private final Map<Key,Flow> flows=new HashMap<Key,Flow>();
    private long lastNow=-1,ownerLookups,ownerAllowed,denied,outboundPackets,inboundPackets,generations;
    private int lastOwnerUid=-1;
    private Decision lastDecision=Decision.NONE,lastOwnerDecision=Decision.NONE;

    public TcpFlowAdmission(OwnerLookup owners,Set<Integer> allowedUids,Set<Integer> deniedUids,int ownUid,int happUid){
        this(owners,allowedUids,deniedUids,ownUid,happUid,Limits.defaults());
    }
    public TcpFlowAdmission(OwnerLookup owners,Set<Integer> allowedUids,Set<Integer> deniedUids,
            int ownUid,int happUid,Limits limits){
        if(owners==null||allowedUids==null||deniedUids==null||limits==null||ownUid<1||happUid<1)
            throw new IllegalArgumentException("explicit_owner_and_uid_policy_required");
        this.owners=owners;this.limits=limits;
        this.allowed=copyUids(allowedUids);Set<Integer> rejected=copyUids(deniedUids);
        Collections.addAll(rejected,0,1000,2000,10005,ownUid,happUid);
        this.deniedUids=Collections.unmodifiableSet(rejected);
    }

    public synchronized boolean outbound(byte[] packet,int length,long elapsedMs){
        if(!advance(elapsedMs))return false;
        Header h=header(packet,length,true);if(h==null)return reject(Decision.INVALID_PACKET);
        Key key=new Key(h.metadata.sourceIp,h.metadata.sourcePort,h.metadata.destinationIp,h.metadata.destinationPort);
        Flow flow=flows.get(key);
        if((h.flags&SYN)!=0){
            if((h.flags&0x3f)!=SYN)return reject(Decision.UNEXPECTED_TCP);
            boolean samePending=flow!=null&&!flow.synAckSeen&&flow.closeAt<0
                    &&flow.clientIsn==h.sequence&&flow.synPayloadLength==h.payloadLength;
            if(samePending&&elapsedMs-flow.lastOwnerCheck<=limits.synOwnerCacheMs){
                flow.lastSeen=elapsedMs;lastDecision=Decision.SYN_RETRANSMIT_CACHED;outboundPackets++;return true;
            }
            // Retire the previous decision before looking up a replacement. A failed
            // recheck cannot leave an old admitted generation usable by ACK/data.
            flows.remove(key);
            if(flows.size()>=limits.maxFlows)return reject(Decision.CAPACITY);
            OwnerObservation observed;
            ownerLookups++;lastOwnerUid=-1;
            try{observed=owners.lookup(h.local.clone(),h.metadata.sourcePort,h.remote.clone(),h.metadata.destinationPort);}
            catch(Exception failure){return ownerReject(Decision.OWNER_LOOKUP_ERROR);}
            if(observed==null||!observed.complete)return ownerReject(Decision.OWNER_INCOMPLETE);
            if(!observed.found)return ownerReject(Decision.OWNER_MISSING);
            if(observed.ambiguous)return ownerReject(Decision.OWNER_AMBIGUOUS);
            if(observed.uid<0)return ownerReject(Decision.OWNER_MISSING);
            lastOwnerUid=observed.uid;
            if(deniedUids.contains(observed.uid))return ownerReject(Decision.OWNER_DENIED);
            if(!allowed.contains(observed.uid))return ownerReject(Decision.OWNER_NOT_ALLOWED);
            ownerAllowed++;lastOwnerDecision=Decision.OWNER_ALLOWED;lastDecision=Decision.OWNER_ALLOWED;
            if(!samePending||flow.ownerUid!=observed.uid){flow=new Flow(h.sequence,h.payloadLength,elapsedMs,++generations,observed.uid);}
            flow.lastOwnerCheck=elapsedMs;flow.lastSeen=elapsedMs;flows.put(key,flow);
            outboundPackets++;return true;
        }
        if(flow==null)return reject(Decision.NO_FLOW);
        if((h.flags&(ACK|RST))==0||(!flow.synAckSeen&&(h.flags&RST)==0))return reject(Decision.UNEXPECTED_TCP);
        if(flow.reset&&((h.flags&RST)==0&&h.payloadLength!=0))return reject(Decision.UNEXPECTED_TCP);
        flow.observe(h,elapsedMs,1,limits);outboundPackets++;lastDecision=Decision.EXISTING_FLOW;return true;
    }

    public synchronized boolean inbound(byte[] packet,int length,long elapsedMs){
        if(!advance(elapsedMs))return false;
        Header h=header(packet,length,false);if(h==null)return reject(Decision.INVALID_PACKET);
        Key key=new Key(h.metadata.destinationIp,h.metadata.destinationPort,h.metadata.sourceIp,h.metadata.sourcePort);
        Flow flow=flows.get(key);if(flow==null)return reject(Decision.NO_FLOW);
        if((h.flags&SYN)!=0){
            if((h.flags&0x3f)!=(SYN|ACK)||!flow.acknowledgesSyn(h.acknowledgment)
                    ||flow.closeAt>=0||flow.synAckSeen&&h.sequence!=flow.serverIsn)return reject(Decision.UNEXPECTED_TCP);
            flow.synAckSeen=true;flow.serverIsn=h.sequence;
        }else{
            if((h.flags&(ACK|RST))==0)return reject(Decision.UNEXPECTED_TCP);
            if(!flow.synAckSeen&&((h.flags&RST)==0||(h.flags&ACK)==0
                    ||!flow.acknowledgesSyn(h.acknowledgment)))return reject(Decision.UNEXPECTED_TCP);
        }
        if(flow.reset&&((h.flags&RST)==0&&h.payloadLength!=0))return reject(Decision.UNEXPECTED_TCP);
        flow.observe(h,elapsedMs,2,limits);inboundPackets++;lastDecision=Decision.EXISTING_FLOW;return true;
    }

    public synchronized Snapshot snapshot(){return new Snapshot(this);}
    private boolean ownerReject(Decision decision){lastOwnerDecision=decision;return reject(decision);}
    private boolean reject(Decision decision){denied++;lastDecision=decision;return false;}
    private boolean advance(long now){
        if(now<0||now>Long.MAX_VALUE-86400000L)return reject(Decision.CLOCK_INVALID);
        if(lastNow>=0&&now<lastNow){flows.clear();lastNow=now;return reject(Decision.CLOCK_REWIND);}
        lastNow=now;
        Iterator<Flow> entries=flows.values().iterator();
        while(entries.hasNext()){
            Flow flow=entries.next();
            if(now-flow.lastSeen>limits.idleMs||now-flow.createdAt>limits.maxLifetimeMs
                    ||!flow.synAckSeen&&now-flow.createdAt>limits.pendingMs
                    ||flow.closeAt>=0&&now>flow.closeAt)entries.remove();
        }
        return true;
    }
    private static Set<Integer> copyUids(Set<Integer> source){
        Set<Integer> result=new HashSet<Integer>();
        for(Integer uid:source){if(uid==null||uid<0)throw new IllegalArgumentException("nonnegative_uid_required");result.add(uid);}
        return result;
    }
    private static final class Flow {
        final long clientIsn,createdAt,generation;
        final int ownerUid,synPayloadLength;
        long lastSeen,lastOwnerCheck,serverIsn,closeAt=-1;
        boolean synAckSeen,reset;int fins;
        Flow(long isn,int synPayload,long now,long generation,int uid){
            clientIsn=isn;synPayloadLength=synPayload;createdAt=now;lastSeen=now;this.generation=generation;ownerUid=uid;
        }
        boolean acknowledgesSyn(long ack){
            // A Fast Open SYN can carry data. A peer may acknowledge only SYN,
            // or also those bytes; it must never acknowledge unsent sequence space.
            return ((ack-((clientIsn+1)&UINT32))&UINT32)<=synPayloadLength;
        }
        void observe(Header h,long now,int direction,Limits limits){
            lastSeen=now;
            if((h.flags&FIN)!=0)fins|=direction;
            if(fins==3&&closeAt<0)closeAt=now+limits.finGraceMs;
            if((h.flags&RST)!=0){
                reset=true;long close=now+limits.rstGraceMs;
                if(closeAt<0||close<closeAt)closeAt=close;
            }
        }
    }
    private static final class Key {
        final String local,remote;final int localPort,remotePort;
        Key(String local,int localPort,String remote,int remotePort){this.local=local;this.localPort=localPort;this.remote=remote;this.remotePort=remotePort;}
        @Override public int hashCode(){return ((local.hashCode()*31+localPort)*31+remote.hashCode())*31+remotePort;}
        @Override public boolean equals(Object value){
            if(!(value instanceof Key))return false;Key other=(Key)value;
            return localPort==other.localPort&&remotePort==other.remotePort&&local.equals(other.local)&&remote.equals(other.remote);
        }
    }
    private static final class Header {
        final PacketMetadata metadata;final byte[] local,remote;
        final int flags,payloadLength;final long sequence,acknowledgment;
        Header(PacketMetadata metadata,byte[] packet,int length,boolean outbound){
            this.metadata=metadata;flags=packet[33]&255;payloadLength=length-20-((packet[32]&255)>>>4)*4;
            sequence=u32(packet,24);acknowledgment=u32(packet,28);
            local=Arrays.copyOfRange(packet,outbound?12:16,outbound?16:20);
            remote=Arrays.copyOfRange(packet,outbound?16:12,outbound?20:16);
        }
    }
    private static Header header(byte[] packet,int length,boolean outbound){
        PacketMetadata m=PacketMetadata.parse(packet,length);
        if(m==null||m.protocol!=6||length<40||length>1500||(packet[0]&255)!=0x45
                ||u16(packet,2)!=length||(u16(packet,6)&0xbfff)!=0||m.sourcePort<1||m.destinationPort<1)return null;
        int tcpHeader=((packet[32]&255)>>>4)*4;
        if(tcpHeader<20||20+tcpHeader>length||(packet[32]&0x0e)!=0)return null;
        int flags=packet[33]&255;
        if((flags&SYN)!=0&&(flags&(FIN|RST))!=0||(flags&(FIN|RST))==(FIN|RST))return null;
        String local=outbound?m.sourceIp:m.destinationIp,remote=outbound?m.destinationIp:m.sourceIp;
        byte[] remoteBytes=Arrays.copyOfRange(packet,outbound?16:12,outbound?20:16);
        if(!DnsPolicy.TUN_IP.equals(local)||DnsPolicy.SERVICE_IP.equals(remote)||!SelectiveSocksGateway.isPublicIpv4(remoteBytes))return null;
        return new Header(m,packet,length,outbound);
    }
    private static int u16(byte[] data,int offset){return(data[offset]&255)*256+(data[offset+1]&255);}
    private static long u32(byte[] data,int offset){return((long)u16(data,offset)<<16)|u16(data,offset+2);}
}
