package local.station.wifi;

/** Independent Python-generated wire fixtures; no Android, sockets or device. */
public final class SynOwnerProbeSpecHostTest {
    private static int assertions;
    private static void check(boolean value,String name){assertions++;if(!value)throw new AssertionError(name);}
    private static byte[] hex(String value){byte[] bytes=new byte[value.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return bytes;}
    public static void main(String[] args){
        byte[] syn=hex("4500002813574000400608f80a6f4d02c61200fe5e305e311234567800000000500240002c530000");
        byte[] ecn=hex("4500002c13574000400608f40a6f4d02c61200fe5e305e31123456780000000060c2400013d70000020405b4");
        check(SynOwnerProbeSpec.matchesInitialSyn(syn,syn.length),"exact tuple initial SYN");
        check(SynOwnerProbeSpec.matchesInitialSyn(ecn,ecn.length),"SYN TCP option and ECN flags");
        check(!SynOwnerProbeSpec.matchesInitialSyn(null,40),"null");
        check(!SynOwnerProbeSpec.matchesInitialSyn(syn,-1),"negative length");
        check(!SynOwnerProbeSpec.matchesInitialSyn(syn,syn.length+1),"length beyond array");
        for(int length=0;length<syn.length;length++)check(!SynOwnerProbeSpec.matchesInitialSyn(syn,length),"truncated "+length);
        for(int offset=0;offset<syn.length;offset++){
            byte[] changed=syn.clone();changed[offset]^=1;
            check(!SynOwnerProbeSpec.matchesInitialSyn(changed,changed.length),"corrupted header/checksum byte "+offset);
        }
        byte[] padded=new byte[1600];System.arraycopy(syn,0,padded,0,syn.length);
        check(SynOwnerProbeSpec.matchesInitialSyn(padded,syn.length),"uses actual TUN read length");
        check(!SynOwnerProbeSpec.matchesInitialSyn(padded,padded.length),"MTU bound");
        check(SynOwnerProbeSpec.validNonce("0123456789abcdef0123456789abcdef"),"fixed nonce");
        for(String invalid:new String[]{null,"","0123456789abcdef","0123456789ABCDEF0123456789ABCDEF","0123456789abcdef0123456789abcdeg","0123456789abcdef0123456789abcdef\n"})
            check(!SynOwnerProbeSpec.validNonce(invalid),"invalid nonce");
        check(SynOwnerProbeSpec.CAPTURE_MS>0&&SynOwnerProbeSpec.CAPTURE_MS<SynOwnerProbeSpec.HARD_MS&&SynOwnerProbeSpec.HARD_MS+100<=6000,"bounded capture and independent hard stop");
        check("198.18.0.254".equals(SynOwnerProbeSpec.DESTINATION)&&"10.111.77.2".equals(SynOwnerProbeSpec.SOURCE),"fixed private test addresses");
        check(SynOwnerProbeSpec.SOURCE_PORT==24112&&SynOwnerProbeSpec.DESTINATION_PORT==24113,"fixed ports");
        System.out.println(assertions+" assertions passed; bounded SYN wire fixtures only; no device/network");
    }
}
