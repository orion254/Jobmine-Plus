package com.jobmineplus.mobile.widgets;

import com.bugsense.trace.BugSenseHandler;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Color;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.FrameLayout;

public class TutorialHelper implements OnClickListener{
    private String prefKey;
    private View tutorialView;
    private FrameLayout frameLayout;
    private SharedPreferences pref;

    public TutorialHelper(Activity activity, int activityLayoutResId, int tutorialLayoutResId, int preferenceResId) {
        this(activity, activityLayoutResId, tutorialLayoutResId, activity.getString(preferenceResId));
    }

    public TutorialHelper(Activity activity, int layoutResId, int tutorialLayoutResId, String preferenceKey) {
        prefKey = preferenceKey;

        frameLayout = new FrameLayout(activity);
        LayoutInflater inflator = (LayoutInflater) activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflator.inflate(layoutResId, frameLayout);
        activity.setContentView(frameLayout);

        // If first time, then build the tutorial and show it
        pref = PreferenceManager.getDefaultSharedPreferences(activity);
        if (!pref.getBoolean(preferenceKey, false)) {
            int numChildren = frameLayout.getChildCount();
            inflator.inflate(tutorialLayoutResId, frameLayout);

            try {
                tutorialView = frameLayout.getChildAt(numChildren);
                tutorialView.setOnClickListener(this);
                tutorialView.setBackgroundColor(Color.argb(99, 0, 0, 0));
                tutorialView.setClickable(true);
            } catch (Exception e) {
                e.printStackTrace();
                BugSenseHandler.sendException(e);
            }
        }
    }

    public View getContentView() {
        return frameLayout;
    }

    @Override
    public void onClick(View v) {
        // Once clicked the tutorial, we do not need to see it again
        if (v.equals(tutorialView)) {
            // Tutorial is finished
            frameLayout.removeView(tutorialView);
            Editor editor = pref.edit();
            editor.putBoolean(prefKey, true);
            editor.commit();
        }
    }
}