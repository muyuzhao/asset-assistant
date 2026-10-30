package local.asset.assistant;
import android.app.job.*;
public final class ReminderJob extends JobService {
    @Override public boolean onStartJob(JobParameters params){try{BackgroundJobs.remind(this,BookStore.shared(this));}catch(Exception ignored){}return false;}
    @Override public boolean onStopJob(JobParameters params){return false;}
}
