package local.station.wifi;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Installed UID mapping seam. Every package sharing an admitted UID must be selected. */
public final class TvUidPolicy {
    public interface Packages {
        int uidForPackage(String name)throws Exception;
        Set<String> packagesForUid(int uid)throws Exception;
    }
    private final Packages packages;
    private final Map<Integer,Set<String>> selected=new HashMap<Integer,Set<String>>();
    private final Set<Integer> denied;
    public TvUidPolicy(Collection<String> requested,Packages packages,Set<Integer> denied)throws Exception{
        if(requested==null||requested.isEmpty()||requested.size()>16||packages==null||denied==null)throw new IllegalArgumentException("explicit_installed_tv_policy_required");
        this.packages=packages;this.denied=new HashSet<Integer>(denied);
        for(String name:requested){
            int uid=packages.uidForPackage(name);
            if(uid<10000||this.denied.contains(uid))throw new IllegalArgumentException("denied_tv_uid");
            Set<String> group=selected.get(uid);if(group==null){group=new HashSet<String>();selected.put(uid,group);}
            if(!group.add(name))throw new IllegalArgumentException("duplicate_tv_package");
        }
        for(Integer uid:selected.keySet())if(!stillAllowed(uid))throw new IllegalArgumentException("shared_uid_not_fully_selected");
    }
    public Set<Integer> allowedUids(){return Collections.unmodifiableSet(new HashSet<Integer>(selected.keySet()));}
    public boolean stillAllowed(int uid){
        Set<String> expected=selected.get(uid);if(expected==null||denied.contains(uid))return false;
        try{
            Set<String> actual=packages.packagesForUid(uid);
            if(actual==null||!actual.equals(expected))return false;
            for(String name:expected)if(packages.uidForPackage(name)!=uid)return false;
            return true;
        }catch(Exception changed){return false;}
    }
}
