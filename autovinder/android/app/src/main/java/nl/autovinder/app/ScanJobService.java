package nl.autovinder.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Android bepaalt de exacte uitvoertijd (Doze/batterijbesparing kunnen vertragen). */
public final class ScanJobService extends JobService {
    private static final int JOB_ID = 51126;

    static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        JobInfo info = new JobInfo.Builder(JOB_ID,new ComponentName(context,ScanJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(60L * 60L * 1000L, 15L * 60L * 1000L)
            .build();
        scheduler.schedule(info);
    }

    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try { FeedClient.fetch(getApplicationContext()); }
            catch (Exception ex) { FeedClient.saveError(getApplicationContext(),ex.getMessage()); }
            jobFinished(params,false);
        }, "MuntvinderFeed").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { return true; }
}
