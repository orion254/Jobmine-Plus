package com.jobmineplus.mobile.services;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;

import com.jobmineplus.mobile.R;
import com.jobmineplus.mobile.activities.jbmnpls.Applications;
import com.jobmineplus.mobile.activities.jbmnpls.Interviews;
import com.jobmineplus.mobile.database.jobs.JobDataSource;
import com.jobmineplus.mobile.database.pages.PageDataSource;
import com.jobmineplus.mobile.database.pages.PageMapResult;
import com.jobmineplus.mobile.debug.activities.DebugApplications;
import com.jobmineplus.mobile.debug.activities.DebugInterviews;
import com.jobmineplus.mobile.exceptions.JbmnplsException;
import com.jobmineplus.mobile.exceptions.JbmnplsLoggedOutException;
import com.jobmineplus.mobile.exceptions.JbmnplsParsingException;
import com.jobmineplus.mobile.widgets.Job;
import com.jobmineplus.mobile.widgets.table.TableParser;
import com.jobmineplus.mobile.widgets.table.TableParserOutline;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;

public class InterviewsNotifierService extends Service {
    private final PageDataSource pageSource = new PageDataSource(this);
    private final JobDataSource jobSource = new JobDataSource(this);
    private final JbmnplsHttpService service = JbmnplsHttpService.getInstance();
    ConnectivityManager connManager;
    NotificationManager mNotificationManager;

    // Nofication values
    private final int INTERVIEW_NOTIFICATION_ID = 1;
    private Notification notification;

    // Time constants
    public final int CRAWL_APPLICATIONS_TIMEOUT = 3 * 60 * 60;  // 3 hours
    public final int NO_DATA_RESCHEDULE_TIME    = 5 * 60 * 60;  // 5 hours

    private int originalTimeout;

    @Override
    public void onCreate() {
        super.onCreate();
        connManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        GetInterviewsTask task = new GetInterviewsTask();
        originalTimeout = intent.getIntExtra(InterviewsAlarm.BUNDLE_TIMEOUT, 0);
        task.execute(originalTimeout);
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private long validateScheduleTime(long timestamp) {
        // This is the next scheduled time
        Calendar nextTime = Calendar.getInstance();
        nextTime.setTimeInMillis(timestamp);

        // Only apply next day if the time is during 12am->9am or if the day is not Sunday or Monday
        int day = nextTime.get(Calendar.DAY_OF_WEEK);
        int hour = nextTime.get(Calendar.HOUR_OF_DAY);
        if (day != Calendar.MONDAY && day != Calendar.SUNDAY && hour < 9) {
            // Return tomorrow at 9am for the next schedule
            Calendar nextDay = Calendar.getInstance();
            nextDay.add(Calendar.DAY_OF_MONTH, 1);
            nextDay.set(Calendar.HOUR_OF_DAY, 9);
            nextDay.set(Calendar.MINUTE, 0);
            return nextDay.getTimeInMillis();
        }
        return timestamp;
    }

    private void scheduleNextAlarm(int timeoutSeconds) {
        // Bundle the next time inside the intent
        long now = System.currentTimeMillis();
        long triggerTime = validateScheduleTime(now + timeoutSeconds * 1000);

        // Pass back the original timeout
        Bundle bundle = new Bundle();
        Intent in = new Intent(this, InterviewsAlarm.class);
        bundle.putInt(InterviewsAlarm.BUNDLE_TIMEOUT, originalTimeout);
        in.putExtra(InterviewsAlarm.BUNDLE_NAME, bundle);

        // Start the next alarm
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, in, PendingIntent.FLAG_ONE_SHOT);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        am.set(AlarmManager.RTC_WAKEUP, triggerTime, pi);
    }

    private void showNotification(String title, String content) {
        if (notification == null) {
            notification = new Notification(R.drawable.ic_launcher,
                    getString(R.string.interviews_ticket_text), System.currentTimeMillis());
        } else {
            notification.when = System.currentTimeMillis();
        }
        Intent resultIntent = new Intent(this, Interviews.class);
        PendingIntent pin = PendingIntent.getActivity(this, 0, resultIntent, 0);
        notification.setLatestEventInfo(this, title, content, pin);
        mNotificationManager.notify(INTERVIEW_NOTIFICATION_ID, notification);
    }

    private class GetInterviewsTask extends AsyncTask<Integer, Void, Boolean>
        implements TableParser.OnTableParseListener {
        private final TableParser parser = new TableParser();
        private ArrayList<Job> pulledJobs;
        private HashMap<String, ArrayList<Job>> pulledAppsJobs;
        private int nextTimeout = 0;

        // Results from getting interviews
        private final int NO_SCHEDULE = 0;
        private final int DO_SCHEDULE = 1;
        private final int DO_SCHEDULE_NO_INTERVIEW = 2;

        public GetInterviewsTask() {
            parser.setOnTableRowParse(this);
        }

        @Override
        protected Boolean doInBackground(Integer... params) {
            nextTimeout = params[0];
            pageSource.open();
            jobSource.open();

            // Check wifi and data
            NetworkInfo mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            NetworkInfo mMobile = connManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            if (mWifi.isConnected() || mMobile.isConnected()) {
                // Check the applications to then see if we need to crawl interviews
                int result = NO_SCHEDULE;
                try {
                    result = checkApplications();
                } catch (JbmnplsLoggedOutException e) {
                    e.printStackTrace();
                    return true;
                } catch (IOException e) {
                    e.printStackTrace();
                    return false;
                }

                // Parse the results
                if (result == NO_SCHEDULE) {
                    return false;
                } else {
                    if (result == DO_SCHEDULE) {
                        return crawlInterviews();
                    } else {    // Do not run interviews but will schedule
                        return true;
                    }
                }
            } else {
                // Try again later
                log("No wifi, try later");
                return true;
            }
        }

        // TODO check if this if statement works with all cases
        private int checkApplications() throws JbmnplsLoggedOutException, IOException {
            PageMapResult result = pageSource.getPageDataMap(Applications.PAGE_NAME);

            // If no results, get them then
            if (result == null) {
                crawlApplications();
                result = pageSource.getPageDataMap(Applications.PAGE_NAME);
                if (result == null) {
                    throw new JbmnplsException("Cannot grab any data from Applications.");
                }
            }

            // Get data from result
            ArrayList<Integer> activeList = result.idMap.get(Applications.LISTS.ACTIVE_JOBS);
            ArrayList<Integer> allList = result.idMap.get(Applications.LISTS.ALL_JOBS);
            long now = System.currentTimeMillis();
            double secDiff = (now - result.timestamp) / 1000;
            Boolean needToGetApps = secDiff > CRAWL_APPLICATIONS_TIMEOUT;

            if (needToGetApps) {
                crawlApplications();
                return checkApplications();
            }

            // When active is empty, we do not need to get interviews
            if (activeList == null) {
                // Both lists are empty so we don't do anything or schedule anything
                if (allList == null) {
                    log("nothing");
                    return NO_SCHEDULE;
                } else {
                    // No active apps and now we reschedule at a later time
                    log("not now");
                    nextTimeout = NO_DATA_RESCHEDULE_TIME;
                    return DO_SCHEDULE_NO_INTERVIEW;
                }
            } else {
                // Check to see if you are employed by chacking the all list for employed
                ArrayList<Job> jobs = jobSource.getJobsByIdList(allList);
                for (Job j : jobs) {
                    if (j.getStatus() == Job.STATUS.EMPLOYED) {
                        // We are employed, no need to check interviews at all
                        log("already employed");
                        return NO_SCHEDULE;
                    }
                }
                log("continuing");
                return DO_SCHEDULE;
            }
        }

        private void crawlApplications() throws JbmnplsLoggedOutException, IOException {
            // Crawl the appplications
            pulledJobs = new ArrayList<Job>();
            pulledAppsJobs = new HashMap<String, ArrayList<Job>>();
            pulledAppsJobs.put(Applications.LISTS.ACTIVE_JOBS, new ArrayList<Job>());
            pulledAppsJobs.put(Applications.LISTS.ALL_JOBS, new ArrayList<Job>());

            // Pull data from the application webpage
            String html = service.getJobmineHtml(DebugApplications.FAKE_APPLICATIONS);
            parser.execute(Applications.ACTIVE_OUTLINE, html);
            parser.execute(Applications.ALL_OUTLINE, html);

            // Put data into storage
            jobSource.addJobs(pulledJobs);
            pageSource.addPage(Applications.PAGE_NAME, pulledAppsJobs, System.currentTimeMillis());
        }

        private Boolean crawlInterviews() {
            // Get interviews data from the database
           ArrayList<Integer> ids = pageSource.getJobsIds(Interviews.PAGE_NAME);
           pulledJobs = new ArrayList<Job>();

           // Pull the interview data off the website
           String html;
           try {
               html = service.getJobmineHtml(DebugInterviews.FAKE_INTERVIEWS);
//                   html = service.getJobmineHtml(JbmnplsHttpService.GET_LINKS.INTERVIEWS);        // Fix for debugging
           } catch (JbmnplsLoggedOutException e) {
               e.printStackTrace();
               return false;
           } catch (IOException e) {
               e.printStackTrace();
               return false;
           }

           // Parse the html into jobs (except the canncelled jobs)
           try {
               parser.execute(Interviews.INTERVIEWS_OUTLINE, html);
               parser.execute(Interviews.GROUPS_OUTLINE, html);
               parser.execute(Interviews.SPECIAL_OUTLINE, html);
           } catch (JbmnplsParsingException e) {
               e.printStackTrace();
               return false;
           }

           // Check to see if this is first time checking interviews on device
           if (ids == null) {
               // First time getting interviews, so we need to add it to the database
               jobSource.addJobs(pulledJobs);
               pageSource.addPage(Interviews.PAGE_NAME, pulledJobs, System.currentTimeMillis());
           } else {
               // Parse out which are the new interviews
               if (pulledJobs.isEmpty()) {
                   return true;
               }

               // Parse the new interviews; remove all jobs that are already existing
               int newCount = 0;
               for (int i = 0; i < pulledJobs.size(); i++) {
                   if (!ids.contains(pulledJobs.get(i).getId())) {
                       newCount++;
                   }
               }

               // No new jobs
               if (newCount == 0) {
                   return true;
               }

               String message = newCount + " new interview"
                       + (newCount==1?"":"s");
               showNotification("Jobmine Plus", message);
           }
           return true;
       }

        @Override
        protected void onPostExecute(Boolean shouldScheduleAlarm) {
            log("finished grabbing", shouldScheduleAlarm);
            pageSource.close();
            jobSource.close();
            if (shouldScheduleAlarm && nextTimeout != 0) {
                scheduleNextAlarm(nextTimeout);   // TODO should enable when not testing
            }
            super.onPostExecute(shouldScheduleAlarm);
        }

        public void onRowParse(TableParserOutline outline, Object... jobData) {
            Job job;
            if (outline == Applications.ALL_OUTLINE) {
                job = Applications.parseRowTableOutline(outline, jobData);
                pulledAppsJobs.get(Applications.LISTS.ALL_JOBS).add(job);
            } else if (outline == Applications.ACTIVE_OUTLINE) {
                job = Applications.parseRowTableOutline(outline, jobData);
                pulledAppsJobs.get(Applications.LISTS.ACTIVE_JOBS).add(job);
            } else {
                job = Interviews.parseRowTableOutline(outline, jobData);
            }
            pulledJobs.add(job);
        }
    }

    protected void log(Object... txt) {
        String returnStr = "";
        int i = 1;
        int size = txt.length;
        if (size != 0) {
            returnStr = txt[0] == null ? "null" : txt[0].toString();
            for (; i < size; i++) {
                returnStr += ", "
                        + (txt[i] == null ? "null" : txt[i].toString());
            }
        }
        System.out.println(returnStr);
    }
}
