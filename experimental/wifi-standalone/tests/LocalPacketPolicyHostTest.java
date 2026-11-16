package local.station.wifi;

import java.util.Arrays;
import java.util.Random;
import local.station.vpnprobe.PacketMetadata;

public final class LocalPacketPolicyHostTest {
    private static int assertions;
    private static final String CLIENT = "10.111.77.2";
    private static final String SERVER = "10.111.77.1";

    public static void main(String[] args) {
        directions();
        flowExpiry();
        flowCap();
        closedFlowCleanup();
        malformedAndBounds();
        fuzz();
        System.out.println(assertions + " assertions passed; offline local TCP admission only; no device/network");
    }

    private static void directions() {
        LocalPacketPolicy policy = new LocalPacketPolicy();
        byte[] syn = packet(CLIENT, 40001, SERVER, 443, 2, 0);
        PacketMetadata parsed = PacketMetadata.parse(syn, syn.length);
        check(parsed != null && CLIENT.equals(parsed.sourceIp) && parsed.sourcePort == 40001
                && parsed.destinationPort == 443 && parsed.protocol == 6, "real metadata parser fixture");
        check(!out(policy, packet(CLIENT, 40001, SERVER, 443, 16, 0), 1), "new ACK cannot open flow");
        check(!in(policy, packet(SERVER, 443, CLIENT, 40001, 18, 0), 1), "unsolicited SYNACK rejected");
        check(out(policy, syn, 2), "local TLS SYN admitted");
        check(in(policy, packet(SERVER, 443, CLIENT, 40001, 18, 0), 3), "matching reverse SYNACK");
        check(out(policy, packet(CLIENT, 40001, SERVER, 443, 24, 12), 4), "existing flow ACK and payload");
        check(in(policy, packet(SERVER, 443, CLIENT, 40001, 24, 16), 5), "matching reverse data");
        check(!in(policy, packet(SERVER, 443, CLIENT, 40002, 16, 0), 6), "wrong client port");
        check(!in(policy, packet(SERVER, 80, CLIENT, 40001, 16, 0), 6), "wrong service port tuple");
        check(!in(policy, packet(SERVER, 443, "10.111.77.3", 40001, 16, 0), 6), "wrong client address");
        check(!in(policy, packet("192.168.77.2", 443, CLIENT, 40001, 16, 0), 6), "wrong server address");
        check(!out(policy, packet(CLIENT, 40001, "1.1.1.1", 443, 2, 0), 6), "no external destination");
        check(!out(policy, packet(CLIENT, 40001, SERVER, 22, 2, 0), 6), "no other service ports");
        check(!out(policy, packet(CLIENT, 40003, SERVER, 80, 18, 0), 6), "new SYNACK cannot open flow");
        check(out(policy, packet(CLIENT, 40003, SERVER, 80, 2, 0), 6), "HTTP admitted separately");
    }

    private static void flowExpiry() {
        LocalPacketPolicy policy = new LocalPacketPolicy();
        byte[] syn = packet(CLIENT, 40001, SERVER, 443, 2, 0);
        byte[] ack = packet(CLIENT, 40001, SERVER, 443, 16, 0);
        byte[] reverse = packet(SERVER, 443, CLIENT, 40001, 16, 0);
        check(out(policy, syn, 0), "open expiry flow");
        check(in(policy, reverse, 600000), "exact idle deadline accepted");
        check(out(policy, ack, 1200000), "reverse traffic refreshes activity");
        check(!in(policy, reverse, 1800001), "expired reverse cannot revive flow");
        check(!out(policy, ack, 1800002), "expired outgoing ACK rejected");
        check(out(policy, syn, 1800003), "new SYN can reopen expired flow");
        LocalPacketPolicy outgoingExpiry = new LocalPacketPolicy();
        check(out(outgoingExpiry, syn, 0), "open outgoing expiry flow");
        check(!out(outgoingExpiry, ack, 600001), "outgoing expiry enforced independently");
    }

    private static void flowCap() {
        LocalPacketPolicy policy = new LocalPacketPolicy();
        for (int i = 0; i < 128; i++)
            check(out(policy, packet(CLIENT, 30000 + i, SERVER, 443, 2, 0), 0), "bounded flow slot " + i);
        check(!out(policy, packet(CLIENT, 31000, SERVER, 443, 2, 0), 1), "129th new flow rejected");
        check(out(policy, packet(CLIENT, 30000, SERVER, 443, 16, 0), 2), "full table still passes existing ACK");
        check(in(policy, packet(SERVER, 443, CLIENT, 30001, 16, 0), 2), "full table still passes reverse");
        check(out(policy, packet(CLIENT, 31000, SERVER, 80, 2, 0), 600003), "expired table capacity reclaimed");
        check(!in(policy, packet(SERVER, 443, CLIENT, 30001, 16, 0), 600004), "purged old flow cannot receive");
    }

    private static void closedFlowCleanup() {
        LocalPacketPolicy policy = new LocalPacketPolicy();
        byte[] syn = packet(CLIENT, 40001, SERVER, 443, 2, 0);
        byte[] ack = packet(CLIENT, 40001, SERVER, 443, 16, 0);
        byte[] fin = packet(CLIENT, 40001, SERVER, 443, 17, 0);
        byte[] reverseFin = packet(SERVER, 443, CLIENT, 40001, 17, 0);
        check(out(policy, syn, 0), "open FIN flow");
        check(out(policy, fin, 10), "outgoing FIN");
        check(in(policy, packet(SERVER,443,CLIENT,40001,24,10), 6010), "half-close permits remaining response data");
        check(in(policy, reverseFin, 6020), "reverse FIN completes close");
        check(out(policy, ack, 6030), "final ACK delivered");
        check(in(policy, reverseFin, 11020), "FIN retransmit at grace boundary");
        check(!out(policy, ack, 11021), "FIN retransmit does not prolong closed flow");
        check(out(policy, syn, 11022), "tuple reusable after close grace");

        LocalPacketPolicy resets = new LocalPacketPolicy();
        check(out(resets,syn,0), "open incoming RST flow");
        check(in(resets,packet(SERVER,443,CLIENT,40001,20,0),10), "incoming RST admitted");
        check(out(resets,ack,1010), "RST grace boundary retained");
        check(!in(resets,reverseFin,1011), "incoming path reaps RST tuple");
        check(out(resets,syn,1012), "reopen for outgoing RST");
        check(out(resets,packet(CLIENT,40001,SERVER,443,4,0),1020), "outgoing RST admitted");
        check(in(resets,packet(SERVER,443,CLIENT,40001,20,0),1500), "reset retransmit admitted");
        check(!out(resets,ack,2021), "reset retransmit does not extend deadline");

        LocalPacketPolicy capacity = new LocalPacketPolicy();
        for(int i=0;i<128;i++)check(out(capacity,packet(CLIENT,30000+i,SERVER,443,2,0),0), "fill active capacity");
        check(in(capacity,packet(SERVER,443,CLIENT,30000,20,0),1), "close one full-table tuple");
        check(!out(capacity,packet(CLIENT,31000,SERVER,443,2,0),1001), "slot held through reset grace");
        check(out(capacity,packet(CLIENT,31000,SERVER,443,2,0),1002), "closed slot released before idle timeout");
        check(out(capacity,packet(CLIENT,30001,SERVER,443,16,0),1003), "other active flows retained");
        check(!in(capacity,packet(SERVER,443,CLIENT,30000,16,0),1004), "retired reverse tuple rejected");
    }

    private static void malformedAndBounds() {
        byte[] valid = packet(CLIENT, 40000, SERVER, 443, 2, 0);
        LocalPacketPolicy policy = new LocalPacketPolicy();
        check(!policy.outbound(null, 40, 0), "null packet");
        check(!policy.outbound(valid, -1, 0), "negative length");
        check(!policy.outbound(valid, valid.length + 1, 0), "length exceeds buffer");
        for (int length = 0; length < valid.length; length++) {
            byte[] shortPacket = Arrays.copyOf(valid, length);
            check(!policy.outbound(shortPacket, length, 0), "all truncation lengths rejected");
        }
        for (int[] edit : new int[][]{{0, 0x65}, {0, 0x44}, {0, 0x4f}, {9, 17}, {3, 39}, {32, 0x40}, {32, 0xf0}}) {
            byte[] bad = valid.clone(); bad[edit[0]] = (byte) edit[1];
            check(!out(policy, bad, 0), "malformed header " + edit[0]);
        }
        for (int flags : new int[]{0x2000, 0x4001, 0x8000}) {
            byte[] bad = valid.clone(); put16(bad, 6, flags);
            check(!out(policy, bad, 0), "fragment/reserved flags rejected");
        }
        for (int offset : new int[]{20, 22}) {
            byte[] bad = valid.clone(); put16(bad, offset, 0);
            check(!out(policy, bad, 0), "zero TCP port rejected");
        }
        check(out(policy, packet(CLIENT, 40002, SERVER, 443, 2, 1460), 1), "MTU1500 admitted");
        check(!out(policy, packet(CLIENT, 40003, SERVER, 443, 2, 1461), 1), "MTU1501 rejected");
        check(!policy.inbound(null, 40, 0), "null inbound");
    }

    private static void fuzz() {
        Random random = new Random(9876);
        LocalPacketPolicy policy = new LocalPacketPolicy();
        for (int i = 0; i < 12000; i++) {
            byte[] input = new byte[random.nextInt(1600)]; random.nextBytes(input);
            policy.outbound(input, input.length, i);
            policy.inbound(input, input.length, i);
        }
    }

    private static boolean out(LocalPacketPolicy p, byte[] packet, long now) { return p.outbound(packet,packet.length,now); }
    private static boolean in(LocalPacketPolicy p, byte[] packet, long now) { return p.inbound(packet,packet.length,now); }
    private static byte[] packet(String source, int sourcePort, String target, int targetPort, int flags, int payload) {
        byte[] result = new byte[40 + payload];
        result[0] = 0x45; put16(result,2,result.length); put16(result,6,0x4000); result[8]=64; result[9]=6;
        address(result,12,source);address(result,16,target);put16(result,20,sourcePort);put16(result,22,targetPort);
        result[27]=1;result[31]=1;result[32]=0x50;result[33]=(byte)flags;put16(result,34,65535);
        for(int i=40;i<result.length;i++)result[i]=(byte)(i&255);
        put16(result,36,checksum(result,20,result.length-20,sum(result,12,8)+6+result.length-20));
        put16(result,10,checksum(result,0,20,0));return result;
    }
    private static void address(byte[] p,int offset,String value) {
        String[] parts=value.split("\\.");for(int i=0;i<4;i++)p[offset+i]=(byte)Integer.parseInt(parts[i]);
    }
    private static int sum(byte[] p,int off,int length) {
        int total=0,end=off+length;for(;off+1<end;off+=2)total+=(p[off]&255)*256+(p[off+1]&255);
        if(off<end)total+=(p[off]&255)<<8;return total;
    }
    private static int checksum(byte[]p,int off,int length,int initial) {
        int value=sum(p,off,length)+initial;while((value>>>16)!=0)value=(value&65535)+(value>>>16);return ~value&65535;
    }
    private static void put16(byte[]p,int off,int value) {p[off]=(byte)(value>>>8);p[off+1]=(byte)value;}
    private static void check(boolean value,String reason) {assertions++;if(!value)throw new AssertionError(reason);}
}
