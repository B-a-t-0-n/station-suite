package local.station.wifi;
import local.station.vpnprobe.PacketMetadata;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** This only admits local-service IPv4 TCP. It is not an internet firewall. */
final class LocalPacketPolicy {
    private final Map<String,Flow> flows=new HashMap<>();
    synchronized boolean outbound(byte[] packet,int length,long now){
        expire(now);
        PacketMetadata m=valid(packet,length);
        if(m==null||!DnsPolicy.SERVICE_IP.equals(m.destinationIp)||(m.destinationPort!=80&&m.destinationPort!=443))return false;
        String key=key(m.sourceIp,m.sourcePort,m.destinationPort);
        Flow flow=flows.get(key);
        if(flow==null){
            int offset=(packet[0]&15)*4;
            if((packet[offset+13]&0x3f)!=2||flows.size()>=128)return false;
            flow=new Flow();flows.put(key,flow);
        }
        flow.observe(packet,now,1);return true;
    }
    synchronized boolean inbound(byte[] packet,int length,long now){
        expire(now);
        PacketMetadata m=valid(packet,length);
        if(m==null||!DnsPolicy.SERVICE_IP.equals(m.sourceIp)||(m.sourcePort!=80&&m.sourcePort!=443))return false;
        String key=key(m.destinationIp,m.destinationPort,m.sourcePort);
        Flow flow=flows.get(key);if(flow==null)return false;
        flow.observe(packet,now,2);return true;
    }
    private void expire(long now){
        Iterator<Flow> ages=flows.values().iterator();
        while(ages.hasNext()){
            Flow flow=ages.next();
            if(now-flow.lastSeen>600000||(flow.closeAt>=0&&now>flow.closeAt))ages.remove();
        }
    }
    private static final class Flow{
        long lastSeen,closeAt=-1;
        int finDirections;
        void observe(byte[] packet,long now,int direction){
            lastSeen=now;int flags=packet[(packet[0]&15)*4+13]&255;
            if((flags&1)!=0)finDirections|=direction;
            // A single FIN is a valid half-close. Reap after both sides finish,
            // allowing final ACK/FIN retransmits without extending the deadline.
            if(finDirections==3&&closeAt<0)closeAt=now+5000;
            if((flags&4)!=0){long resetAt=now+1000;if(closeAt<0||resetAt<closeAt)closeAt=resetAt;}
        }
    }
    private static PacketMetadata valid(byte[] packet,int length){
        PacketMetadata m=PacketMetadata.parse(packet,length);
        if(m==null||m.protocol!=6||length>1500||u16(packet,2)!=length||(u16(packet,6)&0xbfff)!=0)return null;
        int start=(packet[0]&15)*4;
        if(start+20>length||m.sourcePort<1||m.destinationPort<1)return null;
        int header=((packet[start+12]&255)>>>4)*4;
        return header>=20&&start+header<=length?m:null;
    }
    private static int u16(byte[] p,int n){return(p[n]&255)*256+(p[n+1]&255);}
    private static String key(String ip,int a,int b){return ip+":"+a+":"+b;}
}
