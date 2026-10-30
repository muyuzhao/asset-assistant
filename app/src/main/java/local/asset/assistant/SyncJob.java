package local.asset.assistant;
import android.app.job.*;
public final class SyncJob extends JobService {
    private Runnable completion;
    @Override public boolean onStartJob(JobParameters params) {
        try { BookStore book=BookStore.shared(this); if(!book.settings().optBoolean("backgroundSync") || !new Vault(this).exists())return false;
            completion=()->jobFinished(params,false);
            return SyncCoordinator.start(this,book,-1,false,completion);
        } catch(Exception e){return false;}
    }
    @Override public boolean onStopJob(JobParameters params){SyncCoordinator.cancel(completion);return true;}
}
