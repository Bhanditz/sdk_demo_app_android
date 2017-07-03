package com.zendesk.rememberthedate.ui;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuItem;

import com.zendesk.logger.Logger;
import com.zendesk.rememberthedate.BuildConfig;
import com.zendesk.rememberthedate.R;
import com.zendesk.rememberthedate.model.UserProfile;
import com.zendesk.rememberthedate.push.GcmUtil;
import com.zendesk.rememberthedate.push.RegistrationIntentService;
import com.zendesk.rememberthedate.storage.PushNotificationStorage;
import com.zendesk.rememberthedate.storage.UserProfileStorage;
import com.zendesk.sdk.network.impl.DeviceInfo;
import com.zendesk.sdk.model.access.JwtIdentity;
import com.zendesk.sdk.model.request.CustomField;
import com.zendesk.sdk.network.impl.ZendeskConfig;
import com.zendesk.util.FileUtils;
import com.zendesk.util.StringUtils;
import com.zopim.android.sdk.api.ZopimChat;
import com.zopim.android.sdk.model.VisitorInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;


public class MainActivity extends AppCompatActivity implements ActionBar.TabListener, DateFragment.OnFragmentInteractionListener {

    private static final String LOG_TAG = MainActivity.class.getSimpleName();

    public static final String EXTRA_VIEWPAGER_POSITION = "extra_viewpager_pos";
    public static final int VIEWPAGER_POS_DATES = 0;
    public static final int VIEWPAGER_POS_HELP = 1;

    private static final long TICKET_FORM_ID = 62599L;
    private static final long TICKET_FIELD_APP_VERSION = 24328555L;
    private static final long TICKET_FIELD_OS_VERSION = 24273979L;
    private static final long TICKET_FIELD_DEVICE_MODEL = 24273989L;
    private static final long TICKET_FIELD_DEVICE_MEMORY = 24273999L;
    private static final long TICKET_FIELD_DEVICE_FREE_SPACE = 24274009L;
    private static final long TICKET_FIELD_DEVICE_BATTERY_LEVEL = 24274019L;

    /**
     * The {@link android.support.v4.view.PagerAdapter} that will provide
     * fragments for each of the sections. We use a
     * {@link FragmentPagerAdapter} derivative, which will keep every
     * loaded fragment in memory. If this becomes too memory intensive, it
     * may be best to switch to a
     * {@link android.support.v13.app.FragmentStatePagerAdapter}.
     */
    SectionsPagerAdapter mSectionsPagerAdapter;

    /**
     * The {@link ViewPager} that will host the section contents.
     */
    ViewPager mViewPager;

    private UserProfileStorage mStorage;
    private PushNotificationStorage mPushStorage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Logger.setLoggable(true);
        initialiseSdk();

        // Set up the action bar.
        final ActionBar actionBar = getSupportActionBar();
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

        // Create the adapter that will return a fragment for each of the three
        // primary sections of the activity.
        mSectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager());

        // Set up the ViewPager with the sections adapter.
        mViewPager = (ViewPager) findViewById(R.id.pager);
        mViewPager.setAdapter(mSectionsPagerAdapter);

        // When swiping between different sections, select the corresponding
        // tab. We can also use ActionBar.Tab#select() to do this if we have
        // a reference to the Tab.
        mViewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                actionBar.setSelectedNavigationItem(position);
            }
        });

        // For each of the sections in the app, add a tab to the action bar.
        for (int i = 0; i < mSectionsPagerAdapter.getCount(); i++) {
            // Create a tab with text corresponding to the page title defined by
            // the adapter. Also specify this Activity object, which implements
            // the TabListener interface, as the callback (listener) for when
            // this tab is selected.
            actionBar.addTab(
                    actionBar.newTab()
                            .setText(mSectionsPagerAdapter.getPageTitle(i))
                            .setTabListener(this));
        }

        final int viewPagerPosition = getIntent().getIntExtra(EXTRA_VIEWPAGER_POSITION, VIEWPAGER_POS_DATES);
        if(viewPagerPosition < mSectionsPagerAdapter.getCount()) {
            mViewPager.setCurrentItem(viewPagerPosition);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        initialisePush();
    }

    void initialiseSdk() {
        mStorage = new UserProfileStorage(this);
        mPushStorage = new PushNotificationStorage(this);

        final UserProfile profile = mStorage.getProfile();
        if (StringUtils.hasLength(profile.getEmail())) {
            Logger.i("Identity", "Setting identity");
            ZendeskConfig.INSTANCE.setIdentity(new JwtIdentity(profile.getEmail()));

            // Init Zopim Visitor info
            final VisitorInfo.Builder build = new VisitorInfo.Builder()
                    .email(profile.getEmail());

            if (StringUtils.hasLength(profile.getName())) {
                build.name(profile.getName());
            }

            ZopimChat.setVisitorInfo(build.build());
        }

        ZendeskConfig.INSTANCE.setTicketFormId(TICKET_FORM_ID);
        ZendeskConfig.INSTANCE.setCustomFields(getCustomFields());
    }

    private List<CustomField> getCustomFields(){
        final Map deviceInfo = new DeviceInfo(this).getDeviceInfoAsMapForMetaData();

        final String appVersion = String.format(
                Locale.US,
                "version_%s",
                BuildConfig.VERSION_NAME
        );

        final String osVersion = String.format(
                Locale.US,
                "Android %s, Version %s",
                deviceInfo.get("device_os"), deviceInfo.get("device_api")
        );

        final String deviceModel = String.format(
                Locale.US,
                "%s, %s, %s",
                deviceInfo.get("device_model"), deviceInfo.get("device_name"), deviceInfo.get("device_manufacturer")
        );

        final int totalMemory = bytesToMegabytes(Long.parseLong(deviceInfo.get("device_total_memory").toString()));
        final int usedMemory = bytesToMegabytes(Long.parseLong(deviceInfo.get("device_used_memory").toString()));
        final String memoryUsage = String.format(
                Locale.US,
                this.getString(R.string.rate_my_app_dialog_feedback_device_memory),
                totalMemory,
                usedMemory
        );

        final StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        final long bytesAvailable = (long)stat.getBlockSize() * (long)stat.getAvailableBlocks();
        final String freeSpace = FileUtils.humanReadableFileSize(bytesAvailable);

        final String batteryLevel = String.format(Locale.US, "%.1f %s", getBatteryLevel(), "%");

        final List<CustomField> customFields = new ArrayList<>();
        customFields.add(new CustomField(TICKET_FIELD_APP_VERSION, appVersion));
        customFields.add(new CustomField(TICKET_FIELD_OS_VERSION, osVersion));
        customFields.add(new CustomField(TICKET_FIELD_DEVICE_MODEL, deviceModel));
        customFields.add(new CustomField(TICKET_FIELD_DEVICE_MEMORY, memoryUsage));
        customFields.add(new CustomField(TICKET_FIELD_DEVICE_FREE_SPACE, freeSpace));
        customFields.add(new CustomField(TICKET_FIELD_DEVICE_BATTERY_LEVEL, batteryLevel));

        return customFields;
    }

    private int bytesToMegabytes(long bytes) {
        return (int)(Math.round(bytes / 1024.0 / 1024.0));
    }

    public float getBatteryLevel() {
        final Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        final int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        if(level == -1 || scale == -1) {
            return 50.0f;
        }

        return ((float)level / (float)scale) * 100.0f;
    }

    void initialisePush(){
        // Check if we already saved the device' push identifier.
        // If not, enable push.
        if(!mPushStorage.hasPushIdentifier()) {
            enablePush();
        }
    }

    void enablePush(){
        if(GcmUtil.checkPlayServices(this)){
            RegistrationIntentService.start(this);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_add) {
            Intent intent = new Intent(this, CreateDateActivity.class);

            startActivity(intent);
            return true;
        } else if (id == R.id.action_profile) {
            SharedPreferences pSharedPref = this.getApplicationContext().getSharedPreferences("MyDates", Context.MODE_PRIVATE);

            if (pSharedPref.getString("name", "").equals("")) {
                Intent intent = new Intent(this, CreateProfileActivity.class);

                startActivity(intent);
                return true;
            } else {
                Intent intent = new Intent(this, ProfileActivity.class);

                startActivity(intent);
                return true;
            }
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onTabSelected(ActionBar.Tab tab, FragmentTransaction fragmentTransaction) {
        // When the given tab is selected, switch to the corresponding page in
        // the ViewPager.
        mViewPager.setCurrentItem(tab.getPosition());
    }

    @Override
    public void onTabUnselected(ActionBar.Tab tab, FragmentTransaction fragmentTransaction) {
    }

    @Override
    public void onTabReselected(ActionBar.Tab tab, FragmentTransaction fragmentTransaction) {
    }

    /**
     * A {@link FragmentPagerAdapter} that returns a fragment corresponding to
     * one of the sections/tabs/pages.
     */
    public class SectionsPagerAdapter extends FragmentPagerAdapter {

        public SectionsPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int position) {
            // getItem is called to instantiate the fragment for the given page.
            // Return a PlaceholderFragment (defined as a static inner class below).
            if (position == 0) {
                return DateFragment.newInstance();
            }
            else {
                return HelpFragment.newInstance(position+1);
            }
        }

        @Override
        public int getCount() {
            // Show 3 total pages.
            return 2;
        }

        public Drawable getPageIcon(int position) {
            switch (position) {
                case 0:
                    return getResources().getDrawable(R.drawable.ic_home);
                case 1:
                    return null;
            }

            return null;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            Locale l = Locale.getDefault();
            switch (position) {
                case 0:
                    return getString(R.string.title_section1).toUpperCase(l);
                case 1:
                    return getString(R.string.title_section2).toUpperCase(l);
            }
            return null;
        }
    }

    @Override
    public void onFragmentInteraction(String id) {
        Intent intent = new Intent(this, CreateDateActivity.class);
        intent.putExtra("key", id);
        startActivity(intent);

    }
}
