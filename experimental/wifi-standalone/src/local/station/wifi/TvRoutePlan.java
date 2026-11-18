package local.station.wifi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** CIDR complement of the conservative public IPv4 predicate; never a default route. */
public final class TvRoutePlan {
    public static final class Route {
        public final String address;public final int prefix;
        final long start,end;
        private Route(long start,int prefix){this.start=start;this.prefix=prefix;this.end=start+(1L<<(32-prefix))-1;address=text(start);}
        public boolean contains(byte[] address){if(address==null||address.length!=4)return false;long value=value(address);return value>=start&&value<=end;}
    }
    private TvRoutePlan(){}
    public static List<Route> publicIpv4(){
        List<long[]> blocked=new ArrayList<long[]>();
        for(String cidr:new String[]{"0.0.0.0/8","10.0.0.0/8","100.64.0.0/10","127.0.0.0/8",
                "169.254.0.0/16","172.16.0.0/12","192.0.0.0/24","192.0.2.0/24","192.88.99.0/24",
                "192.168.0.0/16","198.18.0.0/15","198.51.100.0/24","203.0.113.0/24","224.0.0.0/3"}){
            String[] split=cidr.split("/");long start=value(split[0]);int prefix=Integer.parseInt(split[1]);
            blocked.add(new long[]{start,start+(1L<<(32-prefix))-1});
        }
        Collections.sort(blocked,(left,right)->Long.compare(left[0],right[0]));
        List<Route> routes=new ArrayList<Route>();long next=0;
        for(long[] range:blocked){if(next<range[0])append(routes,next,range[0]-1);next=Math.max(next,range[1]+1);}
        if(next<=0xffffffffL)append(routes,next,0xffffffffL);
        return Collections.unmodifiableList(routes);
    }
    private static void append(List<Route> result,long start,long end){
        while(start<=end){
            int bits=start==0?32:Math.min(32,Long.numberOfTrailingZeros(start));
            long remaining=end-start+1;while((1L<<bits)>remaining)bits--;
            result.add(new Route(start,32-bits));start+=1L<<bits;
        }
    }
    private static long value(String address){String[] parts=address.split("\\.");long value=0;for(String part:parts)value=(value<<8)|Integer.parseInt(part);return value;}
    private static long value(byte[] address){long value=0;for(byte part:address)value=(value<<8)|(part&255);return value;}
    private static String text(long value){return((value>>>24)&255)+"."+((value>>>16)&255)+"."+((value>>>8)&255)+"."+(value&255);}
}
