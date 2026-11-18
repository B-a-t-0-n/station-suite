package local.station.wifi;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/** Diagnostic TCP tuple lookup. Reads only the two fixed kernel tables; never retains other rows. */
final class ProcSocketOwner {
    private static final int MAX_CHARS=1024*1024, MAX_LINE=1024, MAX_ROWS=8192;
    static final class Tuple {
        final byte[] local,remote;
        final int localPort,remotePort;
        Tuple(byte[] local,int localPort,byte[] remote,int remotePort){
            if(local==null||remote==null||local.length!=4||remote.length!=4
                    ||localPort<1||localPort>65535||remotePort<1||remotePort>65535)throw new IllegalArgumentException("ipv4_tuple_required");
            this.local=local.clone();this.remote=remote.clone();this.localPort=localPort;this.remotePort=remotePort;
        }
    }
    static final class Owner {
        private final Set<Integer> uids=new HashSet<Integer>();
        boolean found(){return !uids.isEmpty();}
        boolean ambiguous(){return uids.size()>1;}
        int uid(){return uids.size()==1?uids.iterator().next():-1;}
    }
    static final class Result {
        final Owner[] owners;
        boolean tcpReadable,tcp6Readable,tcpComplete,tcp6Complete;
        Result(int count){owners=new Owner[count];for(int i=0;i<count;i++)owners[i]=new Owner();}
        boolean complete(){return tcpReadable&&tcp6Readable&&tcpComplete&&tcp6Complete;}
    }
    private ProcSocketOwner(){}

    static Result fromProc(Tuple... tuples){
        Result result=new Result(tuples.length);
        try(Reader input=new FileReader("/proc/net/tcp")){
            result.tcpReadable=true;result.tcpComplete=parse(input,4,tuples,result.owners);
        }catch(IOException denied){}
        try(Reader input=new FileReader("/proc/net/tcp6")){
            result.tcp6Readable=true;result.tcp6Complete=parse(input,16,tuples,result.owners);
        }catch(IOException denied){}
        return result;
    }

    /** Package-private host seam: text fixtures only, never arbitrary filesystem paths. */
    static Result fromText(String tcp,String tcp6,Tuple... tuples)throws IOException{
        Result result=new Result(tuples.length);
        if(tcp!=null){result.tcpReadable=true;result.tcpComplete=parse(new StringReader(tcp),4,tuples,result.owners);}
        if(tcp6!=null){result.tcp6Readable=true;result.tcp6Complete=parse(new StringReader(tcp6),16,tuples,result.owners);}
        return result;
    }

    private static boolean parse(Reader source,int addressBytes,Tuple[] tuples,Owner[] owners)throws IOException{
        BufferedReader input=new BufferedReader(source,4096);
        StringBuilder line=new StringBuilder();int chars=0,rows=0;boolean header=false;
        for(;;){
            int value=input.read();
            if(value<0){if(line.length()>0&&!row(line.toString(),addressBytes,tuples,owners,header))return false;return header;}
            if(++chars>MAX_CHARS)return false;
            if(value=='\n'){
                String text=line.toString().trim();line.setLength(0);
                if(text.isEmpty())continue;
                if(!header){
                    if(!text.contains("local_address")||!(text.contains("rem_address")||text.contains("remote_address")))return false;
                    header=true;continue;
                }
                if(++rows>MAX_ROWS||!row(text,addressBytes,tuples,owners,true))return false;
            }else{
                if(line.length()>=MAX_LINE)return false;
                line.append((char)value);
            }
        }
    }
    private static boolean row(String text,int addressBytes,Tuple[] tuples,Owner[] owners,boolean header){
        if(!header)return false;
        String[] fields=text.trim().split("\\s+");
        if(fields.length<10||!fields[0].matches("[0-9]+:")||!fields[3].matches("[0-9A-Fa-f]{2}"))return false;
        Endpoint local=endpoint(fields[1],addressBytes),remote=endpoint(fields[2],addressBytes);
        if(local==null||remote==null||!fields[7].matches("[0-9]{1,10}"))return false;
        final int uid;
        try{long number=Long.parseLong(fields[7]);if(number>Integer.MAX_VALUE)return false;uid=(int)number;}
        catch(NumberFormatException invalid){return false;}
        // Only a currently connected socket can establish this diagnostic result.
        if(!"01".equals(fields[3]))return true;
        for(int i=0;i<tuples.length;i++){
            Tuple tuple=tuples[i];
            if(local.port==tuple.localPort&&remote.port==tuple.remotePort
                    &&same(local.address,tuple.local)&&same(remote.address,tuple.remote))owners[i].uids.add(uid);
        }
        return true;
    }
    private static final class Endpoint {
        final byte[] address;final int port;
        Endpoint(byte[] address,int port){this.address=address;this.port=port;}
    }
    private static Endpoint endpoint(String value,int size){
        String[] fields=value.split(":",-1);
        if(fields.length!=2||fields[0].length()!=size*2||!fields[0].matches("[0-9A-Fa-f]+")||!fields[1].matches("[0-9A-Fa-f]{4}"))return null;
        byte[] address=new byte[size];
        for(int word=0;word<size;word+=4)for(int offset=0;offset<4;offset++){
            int index=(word+3-offset)*2;address[word+offset]=(byte)Integer.parseInt(fields[0].substring(index,index+2),16);
        }
        return new Endpoint(address,Integer.parseInt(fields[1],16));
    }
    private static boolean same(byte[] actual,byte[] ipv4){
        int start=actual.length==4?0:12;
        if(actual.length==16){
            for(int i=0;i<10;i++)if(actual[i]!=0)return false;
            if((actual[10]&255)!=255||(actual[11]&255)!=255)return false;
        }
        for(int i=0;i<4;i++)if(actual[start+i]!=ipv4[i])return false;
        return true;
    }
}
