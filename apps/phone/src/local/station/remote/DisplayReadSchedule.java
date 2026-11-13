package local.station.remote;

/** Foreground caller supplies time/budget; recovery can issue only read-only DISPLAY GET. */
final class DisplayReadSchedule {
    private static final long NORMAL_DELAY=3500,MAX_BACKOFF=30000;
    private boolean wanted;
    private long nextAt;
    private int failures;

    void refresh(long now){wanted=true;nextAt=now;}
    void clear(){wanted=false;failures=0;nextAt=0;}
    String take(long now,boolean budgetFree){
        if(!wanted||!budgetFree||now<nextAt)return null;
        wanted=false;return DisplayModel.get();
    }
    void received(DisplayModel.Snapshot snapshot,long now){
        if(!snapshot.ok){failed(now);return;}
        failures=0;wanted=snapshot.hasRequest()&&!"applied".equals(snapshot.state);
        nextAt=now+NORMAL_DELAY;
    }
    void failed(long now){
        failures=Math.min(5,failures+1);wanted=true;
        nextAt=now+Math.min(MAX_BACKOFF,NORMAL_DELAY*(1L<<(failures-1)));
    }
}
