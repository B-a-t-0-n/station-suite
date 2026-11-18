package local.station.wifi;

/** Fixed no-forwarding diagnostic, not a configurable Internet admission policy. */
final class SynOwnerProbeSpec {
    static final String DESTINATION="198.18.0.254",SOURCE="10.111.77.2";
    static final int SOURCE_PORT=24112,DESTINATION_PORT=24113,EXPECTED_UID=10005;
    static final long CAPTURE_MS=5000,HARD_MS=5800;
    private static final byte[] SOURCE_BYTES={10,111,77,2},DESTINATION_BYTES={(byte)198,18,0,(byte)254};
    private SynOwnerProbeSpec(){}
    static boolean validNonce(String nonce){return nonce!=null&&nonce.matches("[a-f0-9]{32}");}
    static ProcSocketOwner.Tuple tuple(){return new ProcSocketOwner.Tuple(SOURCE_BYTES,SOURCE_PORT,DESTINATION_BYTES,DESTINATION_PORT);}
    static boolean matchesInitialSyn(byte[] p,int length){
        if(p==null||length<40||length>1500||length>p.length||(p[0]&0xf0)!=0x40)return false;
        int ip=(p[0]&15)*4;
        if(ip<20||ip>60||ip+20>length||u16(p,2)!=length||(u16(p,6)&0xbfff)!=0||(p[9]&255)!=6||checksum(p,0,ip,0)!=0)return false;
        for(int i=0;i<4;i++)if(p[12+i]!=SOURCE_BYTES[i]||p[16+i]!=DESTINATION_BYTES[i])return false;
        if(u16(p,ip)!=SOURCE_PORT||u16(p,ip+2)!=DESTINATION_PORT||(p[ip+13]&0x3f)!=2)return false;
        int tcp=((p[ip+12]&255)>>>4)*4;
        if(tcp<20||ip+tcp!=length)return false;
        // SYN without ACK must not carry a stale acknowledgement number.
        for(int i=ip+8;i<ip+12;i++)if(p[i]!=0)return false;
        int pseudo=sum(p,12,8,0)+6+length-ip;
        return checksum(p,ip,length-ip,pseudo)==0;
    }
    private static int u16(byte[] p,int i){return(p[i]&255)*256+(p[i+1]&255);}
    private static int sum(byte[] p,int start,int count,int total){
        int end=start+count;for(int i=start;i<end;i+=2)total+=(p[i]&255)*256+(i+1<end?p[i+1]&255:0);return total;
    }
    private static int checksum(byte[] p,int start,int count,int total){
        total=sum(p,start,count,total);while((total>>>16)!=0)total=(total&65535)+(total>>>16);return(~total)&65535;
    }
}
