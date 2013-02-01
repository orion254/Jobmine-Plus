package com.jobmineplus.mobile.services;

import java.io.IOException;
import java.util.ArrayList;
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
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;

public class InterviewsNotifierService extends Service {
    private final PageDataSource pageSource = new PageDataSource(this);
    private final JobDataSource jobSource = new JobDataSource(this);
    private final JbmnplsHttpService service = JbmnplsHttpService.getInstance();

    // Nofication values
    private final int INTERVIEW_NOTIFICATION_ID = 1;
    private Notification notification;

    // Time constants
    public final int CRAWL_APPLICATIONS_TIMEOUT = 3 * 60 * 60;  // 3 hours
    public final int NO_DATA_RESCHEDULE_TIME    = 5 * 60 * 60;  // 5 hours

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        GetInterviewsTask task = new GetInterviewsTask();
        task.execute(intent.getIntExtra(InterviewsAlarm.BUNDLE_TIMEOUT, 0));
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void scheduleNextAlarm(int timeoutSeconds) {
        // TODO check if timeout appears in offline time, if so, then move to next day

        // Bundle the next time inside the intent
        long triggerTime = System.currentTimeMillis() + timeoutSeconds * 1000;

        // TODO remove the bundling of timeout
        Bundle bundle = new Bundle();
        Intent in = new Intent(this, InterviewsAlarm.class);
        bundle.putInt(InterviewsAlarm.BUNDLE_TIMEOUT, timeoutSeconds);
        in.putExtra(InterviewsAlarm.BUNDLE_NAME, bundle);

        // Start the next alarm
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, in, PendingIntent.FLAG_ONE_SHOT);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        am.set(AlarmManager.RTC_WAKEUP, triggerTime, pi);
    }

    private void showNotification(String title, String content) {
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
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

            // TODO check wifi

            // Check the applications to then see if we need to crawl interviews
            int result = NO_SCHEDULE;
            try {
                result = checkApplications();
            } catch (JbmnplsLoggedOutException e) {
                e.printStackTrace();
                return false;
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
                    return NO_SCHEDULE;
                } else {
                    // No active apps and now we reschedule at a later time
                    nextTimeout = NO_DATA_RESCHEDULE_TIME;
                    return DO_SCHEDULE_NO_INTERVIEW;
                }
            } else {
                // Check to see if you are employed by chacking the all list for employed
                ArrayList<Job> jobs = jobSource.getJobsByIdList(allList);
                for (Job j : jobs) {
                    if (j.getStatus() == Job.STATUS.EMPLOYED) {
                        // We are employed, no need to check interviews at all
                        return NO_SCHEDULE;
                    }
                }
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
                   html = service.getJobmineHtml(DebugInterviews.FAKE_INTERVIEWS);        // Fix for debugging
//                   html = service.getJobmineHtml("http://10.0.2.2/test/Interviews.html");        // Fix for debugging
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
            pageSource.close();
            jobSource.close();
            log("finished grabbing");
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