package local.station.wifi;

/** Independent Linux table fixtures. No sockets, Android, ADB or filesystem reads. */
public final class ProcSocketOwnerHostTest {
    private static final String HEADER="  sl  local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n";
    private static final String EMPTY=HEADER;
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static String row(String local,String remote,String state,String uid){
        return "  0: "+local+" "+remote+" "+state+" 00000000:00000000 00:00000000 00000000 "+uid+" 0 11111 1\n";
    }
    private static ProcSocketOwner.Tuple tuple(int local,int remote){
        return new ProcSocketOwner.Tuple(new byte[]{127,0,0,1},local,new byte[]{127,0,0,1},remote);
    }
    private static ProcSocketOwner.Result lookup(String tcp,String tcp6)throws Exception{
        return ProcSocketOwner.fromText(tcp,tcp6,tuple(24110,24111),tuple(24111,24110));
    }
    public static void main(String[] args)throws Exception{
        // 24110=5E2E, 24111=5E2F. Kernel words are little endian, including each IPv6 32-bit word.
        String a=row("0100007F:5E2E","0100007F:5E2F","01","10005");
        String b=row("0100007F:5E2F","0100007F:5E2E","01","10123");
        ProcSocketOwner.Result result=lookup(HEADER+a+b,EMPTY);
        check(result.complete(),"both fixed table snapshots complete");
        check(result.owners[0].uid()==10005,"client owner exact tuple");
        check(result.owners[1].uid()==10123,"reverse accepted socket owner is separate");
        check(!result.owners[0].ambiguous(),"one owner");
        result=lookup(EMPTY,HEADER+row("0000000000000000FFFF00000100007F:5e2e","0000000000000000FFFF00000100007F:5e2f","01","10005"));
        check(result.complete()&&result.owners[0].uid()==10005,"IPv4 mapped IPv6 native word order");
        check(!result.owners[1].found(),"direction is not conflated");
        result=lookup(HEADER+row("0200007F:5E2E","0100007F:5E2F","01","10005")
                +row("0100007F:5E2D","0100007F:5E2F","01","10005")
                +row("0100007F:5E2E","0100007F:5E30","01","10005"),EMPTY);
        check(result.complete()&&!result.owners[0].found(),"address and both ports must match");
        for(String state:new String[]{"02","03","06","0A"}){
            result=lookup(HEADER+row("0100007F:5E2E","0100007F:5E2F",state,"10005"),EMPTY);
            check(result.complete()&&!result.owners[0].found(),"only established state "+state);
        }
        result=lookup(HEADER+a+a,EMPTY);check(result.owners[0].uid()==10005,"duplicate same UID remains unambiguous");
        result=lookup(HEADER+a+row("0100007F:5E2E","0100007F:5E2F","01","10006"),EMPTY);
        check(result.owners[0].ambiguous()&&result.owners[0].uid()==-1,"conflicting UID fails closed");
        result=lookup(HEADER+a,null);check(result.tcpReadable&&!result.tcp6Readable&&!result.complete(),"missing table cannot establish complete lookup");
        result=lookup(HEADER+a+"malformed\n",EMPTY);
        check(!result.complete()&&result.owners[0].uid()==10005,"matching row does not hide malformed remainder");
        for(String bad:new String[]{"-1","2147483648","abc","1x"}){
            result=lookup(HEADER+row("0100007F:5E2E","0100007F:5E2F","01",bad),EMPTY);
            check(!result.complete(),"invalid UID "+bad);
        }
        result=lookup(a,EMPTY);check(!result.complete(),"header required");
        result=lookup(HEADER+row("0100007F:5E2E","0100007F:5E2F","01","10005").trim(),EMPTY);
        check(result.complete()&&result.owners[0].uid()==10005,"last row without newline");
        StringBuilder oversized=new StringBuilder(HEADER);for(int i=0;i<1025;i++)oversized.append('a');
        result=lookup(oversized.toString(),EMPTY);check(!result.complete(),"bounded line length");
        oversized=new StringBuilder(HEADER);for(int i=0;i<8193;i++)oversized.append(a);
        result=lookup(oversized.toString(),EMPTY);check(!result.complete(),"bounded row count");
        result=lookup(EMPTY,HEADER+row("0000000000000000000000000100007F:5E2E","0000000000000000000000000100007F:5E2F","01","10005"));
        check(result.complete()&&!result.owners[0].found(),"IPv4 compatible IPv6 is not mapped IPv4");
        System.out.println(assertions+" assertions passed; exact TCP owner fixtures only; no device/network/filesystem");
    }
}
