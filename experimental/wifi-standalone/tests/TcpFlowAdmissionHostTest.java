package local.station.wifi;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** All packets and ownership observations are synthetic; opens no socket or proc file. */
public final class TcpFlowAdmissionHostTest {
    private static int assertions;
    private static final String CLIENT="10.111.77.2",SERVER="1.1.1.1";
    private static final int TV=10050,OTHER_TV=10051,OWN=10040,HAPP=10041;
    private static final TcpFlowAdmission.Limits LIMITS=new TcpFlowAdmission.Limits(2,10000,1000,100,20000,50,20);
    private static final class Lookup implements TcpFlowAdmission.OwnerLookup {
        int calls;boolean throwFailure;
        TcpFlowAdmission.OwnerObservation result=owner(TV);
        @Override public TcpFlowAdmission.OwnerObservation lookup(byte[] local,int lp,byte[] remote,int rp)throws Exception{
            calls++;
            check(Arrays.equals(local,new byte[]{10,111,77,2}),"lookup exact local address");
            check(Arrays.equals(remote,new byte[]{1,1,1,1}),"lookup exact remote address");
            check(lp>=30000&&lp<=65535&&rp==443,"lookup exact ports");
            if(throwFailure)throw new Exception("not retained");return result;
        }
    }
    public static void main(String[] args){
        ownerFailures();directionsAndHandshake();synGenerations();uidPolicy();capacityAndExpiry();
        closeAndReuse();packetBoundsAndScope();clockAndPolicyCopies();fuzz();
        System.out.println(assertions+" assertions passed; offline public TCP UID admission only; no device/network/proc");
    }
    private static void ownerFailures(){
        Lookup lookup=new Lookup();TcpFlowAdmission p=policy(lookup);
        TcpFlowAdmission.OwnerObservation[] observations={null,
            new TcpFlowAdmission.OwnerObservation(false,true,false,TV),
            new TcpFlowAdmission.OwnerObservation(true,false,false,-1),
            new TcpFlowAdmission.OwnerObservation(true,true,true,-1),
            new TcpFlowAdmission.OwnerObservation(true,true,false,-1),owner(10052)};
        TcpFlowAdmission.Decision[] reasons={TcpFlowAdmission.Decision.OWNER_INCOMPLETE,
            TcpFlowAdmission.Decision.OWNER_INCOMPLETE,TcpFlowAdmission.Decision.OWNER_MISSING,
            TcpFlowAdmission.Decision.OWNER_AMBIGUOUS,TcpFlowAdmission.Decision.OWNER_MISSING,
            TcpFlowAdmission.Decision.OWNER_NOT_ALLOWED};
        for(int i=0;i<observations.length;i++){
            lookup.result=observations[i];check(!out(p,syn(30000,5),i),"incomplete/missing/ambiguous/unlisted denied");
            check(p.snapshot().lastOwnerDecision==reasons[i],"owner reason retained without endpoint");
            check(!out(p,ack(30000),i),"denied SYN cannot install ACK flow");
        }
        lookup.throwFailure=true;check(!out(p,syn(30000,5),10),"lookup exception denied");
        check(p.snapshot().lastOwnerDecision==TcpFlowAdmission.Decision.OWNER_LOOKUP_ERROR,"exception reduced to code");
        check(p.snapshot().activeFlows==0,"no flow after any owner failure");
    }
    private static void directionsAndHandshake(){
        Lookup lookup=new Lookup();TcpFlowAdmission p=policy(lookup);
        check(!out(p,ack(30000),0),"first ACK denied");
        check(!in(p,synAck(30000,5,50),0),"unsolicited reverse denied");
        check(out(p,syn(30000,5),1),"TV SYN admitted");
        check(!out(p,ack(30000),2),"ACK before SYNACK denied");
        check(!in(p,synAck(30000,6,50),2),"SYNACK must acknowledge current client generation");
        check(in(p,synAck(30000,5,50),3),"matching SYNACK admitted");
        check(in(p,synAck(30000,5,50),4),"same server SYNACK retransmission");
        check(!in(p,synAck(30000,5,51),4),"different server ISN cannot replace generation");
        check(out(p,ack(30000),5),"ACK after SYNACK admitted");
        check(out(p,packet(CLIENT,30000,SERVER,443,24,6,51,20),6),"outgoing data admitted");
        check(in(p,packet(SERVER,443,CLIENT,30000,24,51,6,20),7),"incoming data admitted");
        check(!in(p,packet(SERVER,443,CLIENT,30001,24,51,6,2),8),"reverse client port exact");
        check(!in(p,packet(SERVER,80,CLIENT,30000,24,51,6,2),8),"reverse remote port exact");
        check(!in(p,packet("8.8.8.8",443,CLIENT,30000,24,51,6,2),8),"reverse address exact");
        check(!out(p,packet(CLIENT,30000,SERVER,80,16,6,51,0),8),"outgoing destination port exact");
        check(lookup.calls==1,"existing packets do not scan owner tables");
        check(p.snapshot().ownerAllowed==1&&p.snapshot().outboundPackets==3,"endpoint-free counters");
        Lookup wrapped=new Lookup();TcpFlowAdmission wrap=policy(wrapped);
        check(out(wrap,syn(30000,0xffffffffL),0),"unsigned initial sequence accepted");
        check(in(wrap,synAck(30000,0xffffffffL,90),1),"SYNACK acknowledgement wraps at uint32");
        Lookup fastOwner=new Lookup();TcpFlowAdmission fast=policy(fastOwner);
        check(out(fast,packet(CLIENT,30000,SERVER,443,2,0xfffffffdL,0,4),0),"Fast Open SYN data is bounded");
        check(!in(fast,packet(SERVER,443,CLIENT,30000,18,90,3,0),1),"Fast Open cannot acknowledge unsent bytes");
        check(in(fast,packet(SERVER,443,CLIENT,30000,18,90,2,0),2),"Fast Open data acknowledgement wraps correctly");
    }
    private static void synGenerations(){
        Lookup lookup=new Lookup();TcpFlowAdmission p=policy(lookup);
        check(out(p,syn(30000,5),0),"initial generation");
        lookup.result=owner(10005);
        check(out(p,syn(30000,5),100),"identical pending SYN caches only through short boundary");
        check(lookup.calls==1,"cache avoids immediate duplicate lookup");
        check(!out(p,syn(30000,5),101),"same ISN rechecked after cache; denied owner rejected");
        check(lookup.calls==2&&p.snapshot().activeFlows==0,"failed recheck retires old generation");
        check(!in(p,synAck(30000,5,50),102),"old SYNACK cannot revive denied generation");
        lookup.result=owner(TV);check(out(p,syn(30000,5),103),"reopen with verified owner");
        lookup.result=owner(10005);
        check(!out(p,syn(30000,6),104),"new ISN never inherits cache");
        check(!out(p,ack(30000),105),"denied replacement leaves no old flow");
        lookup.result=owner(TV);check(out(p,syn(30000,7),106),"next verified generation");
        check(!in(p,synAck(30000,5,50),107),"stale reverse SYNACK rejected after tuple reuse");
        check(in(p,synAck(30000,7,50),108),"fresh reverse accepted");
        int calls=lookup.calls;lookup.result=owner(10005);
        check(!out(p,syn(30000,7),109),"SYN after SYNACK rechecks even same ISN immediately");
        check(lookup.calls==calls+1,"established generation never uses SYN cache");
        Lookup changed=new Lookup();TcpFlowAdmission two=policy(changed);
        check(out(two,syn(30000,5),0),"owner change initial");changed.result=owner(OTHER_TV);
        check(out(two,syn(30000,5),101),"other allowed owner verified afresh");
        check(two.snapshot().generations==2,"changed owner creates distinct generation even same ISN");
    }
    private static void uidPolicy(){
        Lookup lookup=new Lookup();Set<Integer> all=set(0,1000,2000,10005,OWN,HAPP,TV,OTHER_TV,10060);
        TcpFlowAdmission p=new TcpFlowAdmission(lookup,all,set(10060),OWN,HAPP,LIMITS);
        int time=0;
        for(int uid:new int[]{0,1000,2000,10005,OWN,HAPP,10060}){
            lookup.result=owner(uid);check(!out(p,syn(30000,++time),time),"explicit deny overrides allow UID "+uid);
            check(p.snapshot().lastOwnerDecision==TcpFlowAdmission.Decision.OWNER_DENIED,"explicit deny decision");
        }
        lookup.result=owner(TV);check(out(p,syn(30000,99),20),"ordinary TV owner survives deny set");
    }
    private static void capacityAndExpiry(){
        Lookup lookup=new Lookup();TcpFlowAdmission p=policy(lookup);
        check(out(p,syn(30000,1),0)&&out(p,syn(30001,1),0),"bounded pending table filled");
        check(!out(p,syn(30002,1),1),"new flow denied at cap");check(lookup.calls==2,"full cap avoids owner scan");
        check(out(p,syn(30000,2),2),"replacement at cap can reverify its own slot");
        check(out(p,syn(30001,1),100),"pending retransmission accepted");
        check(!out(p,ack(30001),1001),"pending deadline not extended by cached SYN");
        check(out(p,syn(30002,1),1003),"expired pending slots reclaimed");
        Lookup active=new Lookup();TcpFlowAdmission established=policy(active);open(established,30000,5,0);
        check(out(established,ack(30000),10001),"exact idle boundary retained");
        check(!in(established,dataBack(30000),20002),"idle expiry enforced on reverse path");
        open(established,30001,5,20003);
        for(long now:new long[]{30000,39000})check(out(established,ack(30001),now),"refresh lifetime flow");
        check(!out(established,ack(30001),40004),"absolute lifetime expires despite traffic");
    }
    private static void closeAndReuse(){
        Lookup lookup=new Lookup();TcpFlowAdmission p=policy(lookup);open(p,30000,5,0);
        check(out(p,packet(CLIENT,30000,SERVER,443,17,6,51,0),10),"client FIN");
        check(in(p,dataBack(30000),100),"half-close keeps server response");
        check(in(p,packet(SERVER,443,CLIENT,30000,17,51,6,0),110),"server FIN");
        check(out(p,ack(30000),160),"final ACK at grace boundary");
        check(!in(p,dataBack(30000),161),"both FINs expire without grace extension");
        check(out(p,syn(30000,5),162),"same ISN tuple reused only after new lookup");
        check(lookup.calls==2,"closed flow did not retain cached owner");
        check(in(p,packet(SERVER,443,CLIENT,30000,20,51,6,0),163),"pending SYN can receive matching RSTACK");
        check(!out(p,packet(CLIENT,30000,SERVER,443,24,6,51,5),164),"reset generation rejects later payload");
        lookup.result=owner(10005);
        check(!out(p,syn(30000,5),165),"reset forces immediate owner recheck even same ISN");
        check(p.snapshot().activeFlows==0,"denied reuse removes closed generation");
        lookup.result=owner(TV);open(p,30000,8,166);
        check(out(p,packet(CLIENT,30000,SERVER,443,4,9,51,0),170),"outbound RST");
        check(in(p,packet(SERVER,443,CLIENT,30000,20,51,9,0),180),"RST retransmission");
        check(!out(p,ack(30000),191),"RST retransmission cannot extend first grace");
    }
    private static void packetBoundsAndScope(){
        Lookup lookup=new Lookup();TcpFlowAdmission p=policy(lookup);byte[] valid=syn(30000,5);
        check(!p.outbound(null,40,0),"null packet");check(!p.outbound(valid,-1,0),"negative length");
        check(!p.outbound(valid,valid.length+1,0),"length exceeds array");
        for(int n=0;n<40;n++)check(!p.outbound(Arrays.copyOf(valid,n),n,0),"truncated header");
        for(int[] edit:new int[][]{{0,0x65},{0,0x46},{0,0x44},{9,17},{3,39},{32,0x40},{32,0xf0},{32,0x52},{33,3},{33,6}}){
            byte[] bad=valid.clone();bad[edit[0]]=(byte)edit[1];check(!out(p,bad,0),"malformed IP/TCP header");
        }
        for(int fragment:new int[]{1,0x2000,0x4001,0x8000}){
            byte[] bad=valid.clone();put16(bad,6,fragment);check(!out(p,bad,0),"fragments/reserved flag rejected");
        }
        for(int offset:new int[]{20,22}){byte[] bad=valid.clone();put16(bad,offset,0);check(!out(p,bad,0),"zero port");}
        check(!out(p,packet("10.111.77.3",30000,SERVER,443,2,5,0,0),0),"source must equal TUN address");
        for(String target:new String[]{"0.0.0.0","10.111.77.1","10.1.2.3","127.0.0.1","100.64.0.1","169.254.1.1",
                "172.16.0.1","192.168.1.1","192.0.0.1","192.0.2.1","192.88.99.1","198.18.0.254","198.51.100.1","203.0.113.1","224.0.0.1","255.255.255.255"})
            check(!out(p,packet(CLIENT,30000,target,443,2,5,0,0),0),"non-public destination denied "+target);
        check(lookup.calls==0,"invalid/special-address traffic cannot trigger owner lookup");
        check(out(p,packet(CLIENT,30000,SERVER,443,2,5,0,1460),1),"MTU1500 exact bound");
        check(!out(p,packet(CLIENT,30001,SERVER,443,2,5,0,1461),1),"MTU1501 denied");
        byte[] tcpOptions=packet(CLIENT,30001,SERVER,443,2,6,0,4);tcpOptions[32]=0x60;
        check(out(p,tcpOptions,1),"bounded TCP options allowed; IPv4 options rejected");
    }
    private static void clockAndPolicyCopies(){
        Lookup lookup=new Lookup();Set<Integer> allowed=set(TV),denied=set();
        TcpFlowAdmission p=new TcpFlowAdmission(lookup,allowed,denied,OWN,HAPP,LIMITS);
        allowed.clear();denied.add(TV);check(out(p,syn(30000,5),10),"UID inputs defensively copied");
        check(!out(p,ack(30000),9),"clock rewind denied");check(p.snapshot().activeFlows==0,"rewind clears grants");
        check(!out(p,syn(30000,5),-1),"negative clock denied");
        check(!out(p,syn(30000,5),Long.MAX_VALUE),"overflow clock denied");
        boolean rejected=false;try{new TcpFlowAdmission(lookup,set(TV),set(),0,HAPP);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"own/happ identity inputs required");
    }
    private static void fuzz(){
        Lookup lookup=new Lookup();TcpFlowAdmission p=policy(lookup);Random random=new Random(774993L);
        for(int i=0;i<12000;i++){
            byte[] packet=new byte[random.nextInt(1600)];random.nextBytes(packet);
            check(!p.outbound(packet,packet.length,i),"random outgoing denied");
            check(!p.inbound(packet,packet.length,i),"random incoming denied");
        }
        check(lookup.calls==0,"malformed fuzz never queries owner");
    }
    private static TcpFlowAdmission policy(Lookup lookup){return new TcpFlowAdmission(lookup,set(TV,OTHER_TV),set(),OWN,HAPP,LIMITS);}
    private static Set<Integer> set(Integer... values){return new HashSet<Integer>(Arrays.asList(values));}
    private static TcpFlowAdmission.OwnerObservation owner(int uid){return new TcpFlowAdmission.OwnerObservation(true,true,false,uid);}
    private static boolean out(TcpFlowAdmission p,byte[] b,long now){return p.outbound(b,b.length,now);}
    private static boolean in(TcpFlowAdmission p,byte[] b,long now){return p.inbound(b,b.length,now);}
    private static void open(TcpFlowAdmission p,int port,long isn,long now){
        check(out(p,syn(port,isn),now),"open SYN");check(in(p,synAck(port,isn,50),now+1),"open SYNACK");
    }
    private static byte[] syn(int port,long isn){return packet(CLIENT,port,SERVER,443,2,isn,0,0);}
    private static byte[] synAck(int port,long clientIsn,long serverIsn){return packet(SERVER,443,CLIENT,port,18,serverIsn,(clientIsn+1)&0xffffffffL,0);}
    private static byte[] ack(int port){return packet(CLIENT,port,SERVER,443,16,6,51,0);}
    private static byte[] dataBack(int port){return packet(SERVER,443,CLIENT,port,24,51,6,12);}
    private static byte[] packet(String source,int sp,String remote,int rp,int flags,long seq,long ack,int payload){
        byte[] p=new byte[40+payload];p[0]=0x45;put16(p,2,p.length);put16(p,6,0x4000);p[8]=64;p[9]=6;
        address(p,12,source);address(p,16,remote);put16(p,20,sp);put16(p,22,rp);put32(p,24,seq);put32(p,28,ack);
        p[32]=0x50;p[33]=(byte)flags;put16(p,34,65535);for(int n=40;n<p.length;n++)p[n]=(byte)n;
        return p;
    }
    private static void address(byte[] p,int offset,String address){String[] parts=address.split("\\.");for(int i=0;i<4;i++)p[offset+i]=(byte)Integer.parseInt(parts[i]);}
    private static void put16(byte[] p,int offset,int value){p[offset]=(byte)(value>>>8);p[offset+1]=(byte)value;}
    private static void put32(byte[] p,int offset,long value){put16(p,offset,(int)(value>>>16));put16(p,offset+2,(int)value);}
    private static void check(boolean value,String reason){assertions++;if(!value)throw new AssertionError(reason);}
}
