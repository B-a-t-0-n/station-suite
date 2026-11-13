package local.station.remote;

/** Local schematic preview samples. Contains no device, network, microphone or player access. */
final class DotMusicFrames {
    private static final String[] CLOCK={"010110010010111","111001111100111","111001111001111","101101111001001"};
    private static final String[] RANDOM={"wave","pads","bricks","fluid"};
    static float level(String kind,int x,int y,long elapsedMs){
        if(x<0||x>=25||y<0||y>=16)return 0;
        double t=Math.max(0,elapsedMs)/1000d;
        if("random".equals(kind)){
            int index=(int)(t/3.6)%RANDOM.length;double within=t%3.6;
            float a=sample(RANDOM[index],x,y,t);
            if(within<2.8)return a;
            float b=sample(RANDOM[(index+1)%RANDOM.length],x,y,t);
            double blend=(within-2.8)/.8;blend=blend*blend*(3-2*blend);
            return (float)(a+(b-a)*blend);
        }
        return sample(kind,x,y,t);
    }
    private static float sample(String kind,int x,int y,double t){
        double value=0;
        if("clock".equals(kind)){
            int startX=4,startY=5,offset=0;
            for(int i=0;i<4;i++){
                if(i==2)offset+=2;
                int gx=x-startX-offset,gy=y-startY;
                if(gx>=0&&gx<3&&gy>=0&&gy<5&&CLOCK[i].charAt(gy*3+gx)=='1')value=1;
                offset+=4;
            }
            if(x==12&&(y==6||y==8))value=1;
        }else if("wave".equals(kind)){
            double wave=7.5+3.2*Math.sin(x*.31-t*1.7)+1.2*Math.sin(x*.16+t*.9);
            double distance=Math.abs(y-wave);value=Math.exp(-distance*distance/2.8);
        }else if("pads".equals(kind)){
            if(x%5<4&&y%4<3){double phase=(x/5)*.95+(y/4)*1.3;value=.18+.82*(.5+.5*Math.sin(t*1.8+phase));}
        }else if("bricks".equals(kind)){
            double shift=(t*2.1)%6;
            double px=(x+((y/3)%2)*3+shift)%6;
            if(px<4.8&&y%3<2){double edge=Math.min(1,Math.min(px,4.8-px)/.6);value=edge*(.2+.8*(.5+.5*Math.sin(t*1.6+(y/3)*.8+x*.13)));}
        }else if("fluid".equals(kind)){
            double a=Math.sin(x*.28+t*.8),b=Math.sin(y*.4-t*1.1),c=Math.sin((x+y)*.2-t*.6);
            value=Math.pow((a+b+c+3)/6,1.8);
        }
        return (float)Math.max(0,Math.min(1,value));
    }
}
