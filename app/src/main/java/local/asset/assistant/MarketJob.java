package local.asset.assistant;
import android.app.job.*;
public final class MarketJob extends JobService {
    private Runnable completion;
    @Override public boolean onStartJob(JobParameters params){
        try{BookStore book=BookStore.shared(this);BackgroundJobs.opportunity(this,book);if(!MarketReader.due(book,System.currentTimeMillis()))return false;completion=()->jobFinished(params,false);return SyncCoordinator.start(this,book,5,false,completion);}catch(Exception e){return false;}
    }
    @Override public boolean onStopJob(JobParameters params){SyncCoordinator.cancel(completion);return true;}
}
