/*
 *
 * Copyright (c) 2019, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.kin.KinPermissionManager;
import com.psiphon3.psicash.PsiCashClient;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.MainBase;
import com.psiphon3.psiphonlibrary.PsiphonConstants;
import com.psiphon3.psiphonlibrary.TunnelManager;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.psiphonlibrary.Utils.MyLog;
import com.psiphon3.subscription.BuildConfig;
import com.psiphon3.subscription.R;
import com.psiphon3.util.IabHelper;
import com.psiphon3.util.IabResult;
import com.psiphon3.util.Inventory;
import com.psiphon3.util.Purchase;
import com.psiphon3.util.SkuDetails;

import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.ItemNotFoundException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiFunction;
import io.reactivex.schedulers.Schedulers;

public class StatusActivity
    extends com.psiphon3.psiphonlibrary.MainBase.TabbedActivityBase implements PsiCashFragment.ActiveSpeedBoostListener
{
    public static final String ACTION_SHOW_GET_HELP_DIALOG = "com.psiphon3.StatusActivity.SHOW_GET_HELP_CONNECTING_DIALOG";

    private View mRateLimitedTextSection;
    private TextView mRateLimitedText;
    private TextView mRateUnlimitedText;
    private Button mRateLimitSubscribeButton;

    private boolean m_tunnelWholeDevicePromptShown = false;
    private boolean m_loadedSponsorTab = false;
    private boolean m_firstRun = true;
    private static boolean m_startupPending = false;
    private IabHelper mIabHelper = null;
    private boolean mStartIabInProgress = false;
    private boolean mIabHelperIsInitialized = false;

    private PsiCashFragment psiCashFragment;

    private PsiphonAdManager psiphonAdManager;
    private Disposable startUpInterstitialDisposable;
    private boolean disableInterstitialOnNextTabChange;
    private PublishRelay<RateLimitMode> currentRateLimitModeRelay;
    private Disposable currentRateModeDisposable;
    private PublishRelay<Boolean> activeSpeedBoostRelay;
    private KinPermissionManager kinPermissionManager;
    private Disposable toggleClickDisposable;
    private BehaviorRelay<PsiphonAdManager.SubscriptionStatus> subscriptionStatusBehaviorRelay = BehaviorRelay.create();
    private Disposable initializeKinOptInStateDisposable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.main);

        kinPermissionManager = new KinPermissionManager();

        m_tabHost = (TabHost)findViewById(R.id.tabHost);
        m_tabSpecsList = new ArrayList<>();
        m_toggleButton = (Button)findViewById(R.id.toggleButton);

        mRateLimitedTextSection = findViewById(R.id.rateLimitedTextSection);
        mRateLimitedText = (TextView)findViewById(R.id.rateLimitedText);
        mRateUnlimitedText = (TextView)findViewById(R.id.rateUnlimitedText);
        mRateLimitSubscribeButton = (Button)findViewById(R.id.rateLimitUpgradeButton);

        // PsiCash and rewarded video fragment
        FragmentManager fm = getSupportFragmentManager();
        psiCashFragment = (PsiCashFragment) fm.findFragmentById(R.id.psicash_fragment_container);
        psiCashFragment.setActiveSpeedBoostListener(this);

        // rate limit badge observable
        currentRateLimitModeRelay = PublishRelay.create();
        activeSpeedBoostRelay = PublishRelay.create();
        currentRateModeDisposable = Observable.combineLatest(currentRateLimitModeRelay, activeSpeedBoostRelay,
                ((BiFunction<RateLimitMode, Boolean, Pair>) Pair::new))
                .map(pair -> {
                    RateLimitMode rateLimitMode = (RateLimitMode) pair.first;
                    Boolean hasActiveSubscription = (Boolean) pair.second;
                    if (rateLimitMode == RateLimitMode.AD_MODE_LIMITED) {
                        if (hasActiveSubscription) {
                            return RateLimitMode.SPEED_BOOST;
                        } else {
                            return RateLimitMode.AD_MODE_LIMITED;
                        }
                    }
                    return rateLimitMode;
                })
                .doOnNext(this::setRateLimitUI)
                .subscribe();

        // bootstrap the activeSpeedBoost observable
        activeSpeedBoostRelay.accept(Boolean.FALSE);

        // ads
        psiphonAdManager = new PsiphonAdManager(this, findViewById(R.id.largeAdSlot),
                () -> onSubscribeButtonClick(null), true);
        psiphonAdManager.startLoadingAds();

        setupActivityLayout();
        hidePsiCashTab();

        // Listen to GOT_NEW_EXPIRING_PURCHASE intent from psicash module
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE);
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, intentFilter);

        if (isServiceRunning()) {
            // m_firstRun indicates if we should automatically start the tunnel. If the service is
            // already running, we can reset this flag.
            // This mitigates the scenario where the user starts the Activity while the tunnel is
            // running and presses Stop before the IAB flow has completed, causing handleIabFailure
            // to immediately restart the tunnel.
            m_firstRun = false;
        }

        m_loadedSponsorTab = false;
        HandleCurrentIntent();
    }

    private void preventAutoStart() {
        m_firstRun = false;
    }

    private boolean shouldAutoStart() {
        return m_firstRun && !getIntent().getBooleanExtra(INTENT_EXTRA_PREVENT_AUTO_START, false);
    }

    @Override
    protected void restoreSponsorTab() {
        // HandleCurrentIntent() may have already loaded the sponsor tab
        if (isTunnelConnected() && !m_loadedSponsorTab)
        {
            loadSponsorTab(false);
        }
    }

    @Override
    protected void onPause()
    {
        super.onPause();
    }

    @Override
    protected void onResume() {
        initializeKinOptInState();
        startIab();
        super.onResume();
        if (m_startupPending) {
            m_startupPending = false;
            doStartUp();
        }
    }

    @Override
    public void onDestroy() {
        if (startUpInterstitialDisposable != null) {
            startUpInterstitialDisposable.dispose();
        }
        currentRateModeDisposable.dispose();
        psiphonAdManager.onDestroy();
        super.onDestroy();
    }

    private void hidePsiCashTab() {
        m_tabHost
                .getTabWidget()
                .getChildTabViewAt(MainBase.TabbedActivityBase.TabIndex.PSICASH.ordinal())
                .setVisibility(View.GONE);
        // also reset current tab to HOME if PsiCash is currently selected
        String currentTabTag = m_tabHost.getCurrentTabTag();
        if (currentTabTag != null && currentTabTag.equals(PSICASH_TAB_TAG)) {
            disableInterstitialOnNextTabChange = true;
            m_tabHost.setCurrentTabByTag(HOME_TAB_TAG);
        }
    }

    @SuppressLint("CheckResult")
    private void showPsiCashTabIfHasValidToken() {
        // Hide or show the PsiCash tab depending on presence of valid PsiCash tokens.
        // Wrap in Rx Single to run the valid tokens check on a non-UI thread and then
        // update the UI on main thread when we get result.
        Single.fromCallable(() -> PsiCashClient.getInstance(this).hasValidTokens())
                .doOnError(err -> MyLog.g("Error showing PsiCash tab:" + err))
                .onErrorResumeNext(Single.just(Boolean.FALSE))
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(showTab -> {
                    if (showTab) {
                        m_tabHost
                                .getTabWidget()
                                .getChildTabViewAt(MainBase.TabbedActivityBase.TabIndex.PSICASH.ordinal())
                                .setVisibility(View.VISIBLE);
                    } else {
                        hidePsiCashTab();
                    }
                });
    }

    private void loadSponsorTab(boolean freshConnect)
    {
        if (!getSkipHomePage())
        {
            resetSponsorHomePage(freshConnect);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // If the app is already foreground (so onNewIntent is being called),
        // the incoming intent is not automatically set as the activity's intent
        // (i.e., the intent returned by getIntent()). We want this behaviour,
        // so we'll set it explicitly.
        setIntent(intent);

        // Handle explicit intent that is received when activity is already running
        HandleCurrentIntent();
    }

    @Override
    public void onTabChanged(String tabId) {
        if(mayTriggerInterstitial(tabId)) {
            psiphonAdManager.onTabChanged();
        }
        super.onTabChanged(tabId);
    }

    private boolean mayTriggerInterstitial(String tabId) {
        if(disableInterstitialOnNextTabChange) {
            disableInterstitialOnNextTabChange = false;
            return false;
        }
        if(tabId.equals(PSICASH_TAB_TAG)) {
            return false;
        }
        return true;
    }

    @Override
    protected void onTunnelConnectionState(@NonNull TunnelManager.State state) {
        super.onTunnelConnectionState(state);
        TunnelState tunnelState;
        if (state.isRunning) {
            TunnelState.ConnectionData connectionData = TunnelState.ConnectionData.builder()
                    .setIsConnected(state.isConnected)
                    .setClientRegion(state.clientRegion)
                    .setClientVersion(EmbeddedValues.CLIENT_VERSION)
                    .setPropagationChannelId(EmbeddedValues.PROPAGATION_CHANNEL_ID)
                    .setSponsorId(state.sponsorId)
                    .setHttpPort(state.listeningLocalHttpProxyPort)
                    .setVpnMode(state.isVPN)
                    .build();
            tunnelState = TunnelState.running(connectionData);
        } else {
            tunnelState = TunnelState.stopped();
        }

        psiphonAdManager.onTunnelConnectionState(tunnelState);
        psiCashFragment.onTunnelConnectionState(tunnelState);
    }

    @Override
    protected void onAuthorizationsRemoved() {
        MyLog.g("PsiCash: received onAuthorizationsRemoved() notification");
        super.onAuthorizationsRemoved();
        psiCashFragment.removePurchases(getApplicationContext());
    }

    protected void HandleCurrentIntent()
    {
        Intent intent = getIntent();

        if (intent == null || intent.getAction() == null)
        {
            return;
        }

        if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_HANDSHAKE))
        {
            onTunnelConnectionState(getTunnelStateFromBundle(intent.getExtras()));

            // OLD COMMENT:
            // Show the home page. Always do this in browser-only mode, even
            // after an automated reconnect -- since the status activity was
            // brought to the front after an unexpected disconnect. In whole
            // device mode, after an automated reconnect, we don't re-invoke
            // the browser.
            // UPDATED:
            // We don't bring the status activity to the front after an
            // unexpected disconnect in browser-only mode any more.
            // Show the home page, unless this was an automatic reconnect,
            // since the homepage should already be showing.
            // UPDATED #2:
            // This intent is only sent when there was a commanded service start or a restart
            // such as when there's a new subscription, or a speed-boost. It is not sent
            // on automated reconnects or when the app binds to a an already running service.
            // In later case the embedded web view gets updated via MSG_TUNNEL_CONNECTION_STATE
            // messages from a bound service.

            // TODO: only switch to home tab when there's an in app home page to show?
            disableInterstitialOnNextTabChange = true;
            m_tabHost.setCurrentTabByTag(HOME_TAB_TAG);
            loadSponsorTab(true);
            m_loadedSponsorTab = true;

            // We only want to respond to the HANDSHAKE_SUCCESS action once,
            // so we need to clear it (by setting it to a non-special intent).
            setIntent(new Intent(
                            "ACTION_VIEW",
                            null,
                            this,
                            this.getClass()));
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_SELECTED_REGION_NOT_AVAILABLE)) {
            // Switch to settings tab
            disableInterstitialOnNextTabChange = true;
            m_tabHost.setCurrentTabByTag(SETTINGS_TAB_TAG);

            // Set egress region preference to 'Best Performance'
            updateEgressRegionPreference(PsiphonConstants.REGION_CODE_ANY);

            // Set region selection to 'Best Performance' too
            m_regionSelector.setSelectionByValue(PsiphonConstants.REGION_CODE_ANY);

            // Show "Selected region unavailable" toast
            Toast toast = Toast.makeText(this, R.string.selected_region_currently_not_available, Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();

            // We only want to respond to the INTENT_ACTION_SELECTED_REGION_NOT_AVAILABLE action once,
            // so we need to clear it (by setting it to a non-special intent).
            setIntent(new Intent(
                    "ACTION_VIEW",
                    null,
                    this,
                    this.getClass()));
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_VPN_REVOKED)) {
            showVpnAlertDialog(R.string.StatusActivity_VpnRevokedTitle, R.string.StatusActivity_VpnRevokedMessage);
        } else if (0 == intent.getAction().compareTo(ACTION_SHOW_GET_HELP_DIALOG)) {
            // OK to be null because we don't use it
            onGetHelpConnectingClick(null);
        }
    }

    public void onToggleClick(View v) {
        // Only check for payment when starting in WDM, also make sure subscribers don't get prompted for Kin.
        if (!isServiceRunning() && getTunnelConfigWholeDevice() && !Utils.getHasValidSubscription(getApplicationContext())) {
            // prevent multiple confirmation dialogs
            if (toggleClickDisposable != null && !toggleClickDisposable.isDisposed()) {
                return;
            }

            toggleClickDisposable = Single.fromCallable(() -> kinPermissionManager.isOptedIn(getApplicationContext()))
                    .flatMap(optedIn -> optedIn ? kinPermissionManager.confirmDonation(this) : Single.just(false))
                    .doOnSuccess(this::setKinState)
                    .doOnSuccess(__ -> doToggle())
                    .subscribe();
        } else {
            doToggle();
        }
    }

    public void onOpenBrowserClick(View v)
    {
        displayBrowser(this, null);
    }

    public void onGetHelpConnectingClick(View v) {
        showConnectionHelpDialog(this, R.layout.dialog_get_help_connecting);
    }

    public void onHowToHelpClick(View view) {
        showConnectionHelpDialog(this, R.layout.dialog_how_to_help_connect);
    }

    @Override
    public void onFeedbackClick(View v)
    {
        Intent feedbackIntent = new Intent(this, FeedbackActivity.class);
        startActivity(feedbackIntent);
    }

    public void onKinEnabledClick(View v) {
        // Assume this will always be the kin enabled checkbox
        CheckBox checkBox = (CheckBox) v;
        // Prevent the default toggle, that's handled automatically by a subscription to the opted-in state
        checkBox.setChecked(!checkBox.isChecked());
        if (kinPermissionManager.isOptedIn(this)) {
            kinPermissionManager
                    .optOut(this)
                    .doOnSuccess(this::setKinState)
                    .subscribe();
        } else {
            kinPermissionManager
                    .optIn(this)
                    .doOnSuccess(this::setKinState)
                    .subscribe();
        }
    }

    @Override
    protected void startUp() {
        if (startUpInterstitialDisposable != null && !startUpInterstitialDisposable.isDisposed()) {
            // already in progress, do nothing
            return;
        }
        int countdownSeconds = 10;
        startUpInterstitialDisposable = psiphonAdManager.getCurrentAdTypeObservable()
                .take(1)
                .switchMap(adResult -> {
                    if (adResult.type() == PsiphonAdManager.AdResult.Type.NONE) {
                        doStartUp();
                        return Observable.empty();
                    }
                    else if (adResult.type() == PsiphonAdManager.AdResult.Type.TUNNELED) {
                        MyLog.g("startUp interstitial bad ad type: " + adResult.type());
                        return Observable.empty();
                    }

                    Observable<PsiphonAdManager.InterstitialResult> interstitial =
                            Observable.just(adResult)
                                    .compose(psiphonAdManager.getInterstitialWithTimeoutForAdType(countdownSeconds, TimeUnit.SECONDS))
                                    .doOnNext(interstitialResult -> {
                                        if (interstitialResult.state() == PsiphonAdManager.InterstitialResult.State.READY) {
                                            m_startupPending = true;
                                            interstitialResult.show();
                                        }
                                    })
                                    .doOnComplete(() -> {
                                        if(m_startupPending) {
                                            m_startupPending = false;
                                            doStartUp();
                                        }
                                    });

                    Observable<Long> countdown =
                            Observable.intervalRange(0, countdownSeconds, 0, 1, TimeUnit.SECONDS)
                                    .map(t -> countdownSeconds - t)
                                    .concatWith(Observable.error(new TimeoutException("Ad countdown timeout.")))
                                    .doOnNext(t -> runOnUiThread(() ->m_toggleButton.setText(String.format(Locale.US, "%d", t))));

                    return countdown
                            .takeUntil(interstitial)
                            .doOnError(__->doStartUp());
                })
                .onErrorResumeNext(Observable.empty())
                .subscribe();
    }

    private void doStartUp()
    {
        // cancel any ongoing startUp subscription
        if(startUpInterstitialDisposable != null) {
            startUpInterstitialDisposable.dispose();
        }
        // If the user hasn't set a whole-device-tunnel preference, show a prompt
        // (and delay starting the tunnel service until the prompt is completed)

        boolean hasPreference;
        AppPreferences mpPreferences = new AppPreferences(this);
        try {
            mpPreferences.getBoolean(TUNNEL_WHOLE_DEVICE_PREFERENCE);
            hasPreference = true;
        } catch (ItemNotFoundException e) {
            hasPreference = false;
        }

        if (Utils.hasVpnService() &&
            !hasPreference &&
            !isServiceRunning())
        {
            if (!m_tunnelWholeDevicePromptShown && !this.isFinishing())
            {
                final Context context = this;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        AlertDialog dialog = new AlertDialog.Builder(context)
                                .setCancelable(false)
                                .setOnKeyListener(
                                        new DialogInterface.OnKeyListener() {
                                            @Override
                                            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                                                // Don't dismiss when hardware search button is clicked (Android 2.3 and earlier)
                                                return keyCode == KeyEvent.KEYCODE_SEARCH;
                                            }
                                        })
                                .setTitle(R.string.StatusActivity_WholeDeviceTunnelPromptTitle)
                                .setMessage(R.string.StatusActivity_WholeDeviceTunnelPromptMessage)
                                .setPositiveButton(R.string.StatusActivity_WholeDeviceTunnelPositiveButton,
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int whichButton) {
                                                // Persist the "on" setting
                                                updateWholeDevicePreference(true);
                                                startTunnel();
                                            }
                                        })
                                .setNegativeButton(R.string.StatusActivity_WholeDeviceTunnelNegativeButton,
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int whichButton) {
                                                // Turn off and persist the "off" setting
                                                m_tunnelWholeDeviceToggle.setChecked(false);
                                                updateWholeDevicePreference(false);
                                                startTunnel();
                                            }
                                        })
                                .setOnCancelListener(
                                        new DialogInterface.OnCancelListener() {
                                            @Override
                                            public void onCancel(DialogInterface dialog) {
                                                // Don't change or persist preference (this prompt may reappear)
                                                startTunnel();
                                            }
                                        })
                                .show();

                        // Our text no longer fits in the AlertDialog buttons on Lollipop, so force the
                        // font size (on older versions, the text seemed to be scaled down to fit).
                        // TODO: custom layout
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                        {
                            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                        }
                    }
                });

                m_tunnelWholeDevicePromptShown = true;
            }
            else
            {
                // ...there's a prompt already showing (e.g., user hit Home with the
                // prompt up, then resumed Psiphon)
            }

            // ...wait and let onClick handlers will start tunnel
        }
        else
        {
            // No prompt, just start the tunnel (if not already running)
            startTunnel();
        }
    }

    @Override
    public void displayBrowser(Context context, String urlString, boolean shouldPsiCashModifyUrls) {
        if (urlString == null) {
            ArrayList<String> homePages = getHomePages();
            if (homePages.size() > 0) {
                urlString = homePages.get(0);
            }
        }

        if (shouldPsiCashModifyUrls) {
            // Add PsiCash parameters
            urlString = PsiCashModifyUrl(urlString);
        }

        // Notify PsiCash fragment so it will know to refresh state on next app foreground.
        psiCashFragment.onOpenHomePage();

        try {
            if (getTunnelConfigWholeDevice()) {
                // TODO: support multiple home pages in whole device mode. This is
                // disabled due to the case where users haven't set a default browser
                // and will get the prompt once per home page.

                // If URL is not empty we will try to load in an external browser, otherwise we will
                // try our best to open an external browser instance without specifying URL to load
                // or will load "about:blank" URL if that fails.

                // Prepare browser starting intent.
                Intent browserIntent;
                if (TextUtils.isEmpty(urlString)) {
                    // If URL is empty, just start the app.
                    browserIntent = new Intent(Intent.ACTION_MAIN);
                } else {
                    // If URL is not empty, start the app with URL load intent.
                    browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlString));
                }
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                // query default 'URL open' intent handler.
                Intent queryIntent;
                if (TextUtils.isEmpty(urlString)) {
                    queryIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.org"));
                } else {
                    queryIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlString));
                }
                ResolveInfo resolveInfo = getPackageManager().resolveActivity(queryIntent, PackageManager.MATCH_DEFAULT_ONLY);

                // Try and start default intent handler application if there is one
                if (resolveInfo != null &&
                        resolveInfo.activityInfo != null &&
                        resolveInfo.activityInfo.name != null &&
                        !resolveInfo.activityInfo.name.toLowerCase().contains("resolver")) {
                    browserIntent.setClassName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name);
                    context.startActivity(browserIntent);
                } else { // There is no default handler, try chrome
                    browserIntent.setPackage("com.android.chrome");
                    try {
                        context.startActivity(browserIntent);
                    } catch (ActivityNotFoundException ex) {
                        // We tried to open Chrome and it is not installed,
                        // so reinvoke with the default behaviour
                        browserIntent.setPackage(null);
                        // If URL is empty try loading a special URL 'about:blank'
                        if (TextUtils.isEmpty(urlString)) {
                            browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("about:blank"));
                        }
                        context.startActivity(browserIntent);
                    }
                }
            } else {
                Uri uri = null;
                if (!TextUtils.isEmpty(urlString)) {
                    uri = Uri.parse(urlString);
                }

                Intent intent = new Intent(
                        "ACTION_VIEW",
                        uri,
                        context,
                        org.zirco.ui.activities.MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                // This intent displays the Zirco browser.
                // We use "extras" to communicate Psiphon settings to Zirco.
                // When Zirco is first created, it will use the homePages
                // extras to open tabs for each home page, respectively. When the intent
                // triggers an existing Zirco instance (and it's a singleton) this extra
                // is ignored and the browser is displayed as-is.
                // When a uri is specified, it will open as a new tab. This is
                // independent of the home pages.
                // Note: Zirco now directly accesses PsiphonData to get the current
                // local HTTP proxy port for WebView tunneling.

                // Add PsiCash parameters to home pages
                ArrayList<String> homePages = new ArrayList<>();
                for (String url : getHomePages()) {
                    if(!TextUtils.isEmpty(url)) {
                        if(shouldPsiCashModifyUrls) {
                            // Add PsiCash parameters
                            url = PsiCashModifyUrl(url);
                        }
                        homePages.add(url);
                    }
                }

                intent.putExtra("localProxyPort", getListeningLocalHttpProxyPort());
                intent.putExtra("homePages", homePages);

                context.startActivity(intent);
            }
        } catch (ActivityNotFoundException e) {
            // Thrown by startActivity; in this case, we ignore and the URI isn't opened
        }
    }

    static final String IAB_PUBLIC_KEY = BuildConfig.IAB_PUBLIC_KEY;
    static final int IAB_REQUEST_CODE = 10001;

    static final String IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU = "speed_limited_ad_free_subscription";
    static final String[] IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKUS = {IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU};
    static final String IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU = "basic_ad_free_subscription_5";
    static final String[] IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKUS = {IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU,
            "basic_ad_free_subscription", "basic_ad_free_subscription_2", "basic_ad_free_subscription_3", "basic_ad_free_subscription_4"};

    static final String IAB_BASIC_7DAY_TIMEPASS_SKU = "basic_ad_free_7_day_timepass";
    static final String IAB_BASIC_30DAY_TIMEPASS_SKU = "basic_ad_free_30_day_timepass";
    static final String IAB_BASIC_360DAY_TIMEPASS_SKU = "basic_ad_free_360_day_timepass";
    static final Map<String, Long> IAB_TIMEPASS_SKUS_TO_TIME;
    static {
        Map<String, Long> m = new HashMap<>();
        m.put(IAB_BASIC_7DAY_TIMEPASS_SKU, 7L * 24 * 60 * 60 * 1000);
        m.put(IAB_BASIC_30DAY_TIMEPASS_SKU, 30L * 24 * 60 * 60 * 1000);
        m.put(IAB_BASIC_360DAY_TIMEPASS_SKU, 360L * 24 * 60 * 60 * 1000);
        IAB_TIMEPASS_SKUS_TO_TIME = Collections.unmodifiableMap(m);
    }

    @Override
    public void onActiveSpeedBoost(Boolean hasActiveSpeedBoost) {
        activeSpeedBoostRelay.accept(hasActiveSpeedBoost);
    }

    enum RateLimitMode {AD_MODE_LIMITED, LIMITED_SUBSCRIPTION, UNLIMITED_SUBSCRIPTION, SPEED_BOOST}

    Inventory mInventory;

    synchronized
    private void startIab()
    {
        if (mStartIabInProgress)
        {
            return;
        }

        if (mIabHelper == null)
        {
            mStartIabInProgress = true;
            mIabHelper = new IabHelper(this, IAB_PUBLIC_KEY);
            mIabHelper.startSetup(m_iabSetupFinishedListener);
        }
        else
        {
            queryInventory();
        }
    }

    private boolean isIabInitialized() {
        return mIabHelper != null && mIabHelperIsInitialized;
    }

    private IabHelper.OnIabSetupFinishedListener m_iabSetupFinishedListener =
            new IabHelper.OnIabSetupFinishedListener()
    {
        @Override
        public void onIabSetupFinished(IabResult result)
        {
            mStartIabInProgress = false;

            if (result.isFailure())
            {
                Utils.MyLog.g(String.format("StatusActivity::onIabSetupFinished: failure: %s", result));
                handleIabFailure(result);
            }
            else
            {
                mIabHelperIsInitialized = true;
                Utils.MyLog.g(String.format("StatusActivity::onIabSetupFinished: success: %s", result));
                queryInventory();
            }
        }
    };

    private IabHelper.QueryInventoryFinishedListener m_iabQueryInventoryFinishedListener =
            new IabHelper.QueryInventoryFinishedListener()
    {
        @Override
        public void onQueryInventoryFinished(IabResult result, Inventory inventory)
        {
            if (result.isFailure())
            {
                Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: failure: %s", result));
                handleIabFailure(result);
                return;
            }

            mInventory = inventory;

            boolean hasValidSubscription = false;

            //
            // Check if the user has a subscription.
            //

            RateLimitMode rateLimit = RateLimitMode.AD_MODE_LIMITED;
            Purchase purchase = null;

            for (String limitedMonthlySubscriptionSku : IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKUS) {
                if (inventory.hasPurchase(limitedMonthlySubscriptionSku)) {
                    Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: has valid limited subscription: %s", limitedMonthlySubscriptionSku));
                    purchase = inventory.getPurchase(limitedMonthlySubscriptionSku);
                    rateLimit = RateLimitMode.LIMITED_SUBSCRIPTION;
                    currentRateLimitModeRelay.accept(rateLimit);
                    hasValidSubscription = true;
                    break;
                }
            }

            for (String unlimitedMonthlySubscriptionSku : IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKUS) {
                if (inventory.hasPurchase(unlimitedMonthlySubscriptionSku)) {
                    Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: has valid unlimited subscription: %s", unlimitedMonthlySubscriptionSku));
                    purchase = inventory.getPurchase(unlimitedMonthlySubscriptionSku);
                    rateLimit = RateLimitMode.UNLIMITED_SUBSCRIPTION;
                    currentRateLimitModeRelay.accept(rateLimit);
                    hasValidSubscription = true;
                    break;
                }
            }

            if (hasValidSubscription)
            {
                proceedWithValidSubscription(purchase);
                return;
            }

            //
            // Check if the user has purchased a (30-day) time pass.
            //

            long now = System.currentTimeMillis();
            List<Purchase> timepassesToConsume = new ArrayList<>();
            for (Map.Entry<String, Long> timepass : IAB_TIMEPASS_SKUS_TO_TIME.entrySet())
            {
                String sku = timepass.getKey();
                long lifetime = timepass.getValue();

                // DEBUG: This line will convert days to minutes. Useful for testing.
                //lifetime = lifetime / 24 / 60;

                Purchase tempPurchase = inventory.getPurchase(sku);
                if (tempPurchase == null)
                {
                    continue;
                }

                long timepassExpiry = tempPurchase.getPurchaseTime() + lifetime;
                if (now < timepassExpiry)
                {
                    // This time pass is still valid.
                    Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: has valid time pass: %s", sku));
                    rateLimit = RateLimitMode.UNLIMITED_SUBSCRIPTION;
                    currentRateLimitModeRelay.accept(rateLimit);
                    hasValidSubscription = true;
                    purchase = tempPurchase;
                }
                else
                {
                    // This time pass is no longer valid. Consider it invalid and consume it below.
                    Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: consuming old time pass: %s", sku));
                    timepassesToConsume.add(tempPurchase);
                }
            }

            if (hasValidSubscription)
            {
                proceedWithValidSubscription(purchase);
            }
            else
            {
                // There is no valid subscription or time pass for this user.
                Utils.MyLog.g("StatusActivity::onQueryInventoryFinished: no valid subscription or time pass");
                proceedWithoutValidSubscription();
            }

            if (timepassesToConsume.size() > 0)
            {
                consumePurchases(timepassesToConsume);
            }
        }
    };

    private IabHelper.OnIabPurchaseFinishedListener m_iabPurchaseFinishedListener =
            new IabHelper.OnIabPurchaseFinishedListener()
    {
        @Override
        public void onIabPurchaseFinished(IabResult result, Purchase purchase)
        {
            if (result.isFailure())
            {
                Utils.MyLog.g(String.format("StatusActivity::onIabPurchaseFinished: failure: %s", result));
                handleIabFailure(result);
            }
            else if (purchase.getSku().equals(IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU))
            {
                Utils.MyLog.g(String.format("StatusActivity::onIabPurchaseFinished: success: %s", purchase.getSku()));
                currentRateLimitModeRelay.accept(RateLimitMode.LIMITED_SUBSCRIPTION);
                proceedWithValidSubscription(purchase);
            }
            else if (purchase.getSku().equals(IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU))
            {
                Utils.MyLog.g(String.format("StatusActivity::onIabPurchaseFinished: success: %s", purchase.getSku()));
                currentRateLimitModeRelay.accept(RateLimitMode.UNLIMITED_SUBSCRIPTION);
                proceedWithValidSubscription(purchase);
            }
            else if (IAB_TIMEPASS_SKUS_TO_TIME.containsKey(purchase.getSku()))
            {
                Utils.MyLog.g(String.format("StatusActivity::onIabPurchaseFinished: success: %s", purchase.getSku()));

                // We're not going to check the validity time here -- assume no time-pass is so
                // short that it's already expired right after it's purchased.
                currentRateLimitModeRelay.accept(RateLimitMode.UNLIMITED_SUBSCRIPTION);
                proceedWithValidSubscription(purchase);
            }
        }
    };

    private IabHelper.OnConsumeMultiFinishedListener m_iabConsumeFinishedListener =
            new IabHelper.OnConsumeMultiFinishedListener()
    {
        @Override
        public void onConsumeMultiFinished(List<Purchase> purchases, List<IabResult> results)
        {
            boolean failed = false;
            for (IabResult result : results)
            {
                if (result.isFailure())
                {
                    Utils.MyLog.g(String.format("StatusActivity::onConsumeMultiFinished: failure: %s", result));
                    failed = true;
                }
                else
                {
                    Utils.MyLog.g("StatusActivity::onConsumeMultiFinished: success");
                }
            }

            if (failed)
            {
                handleIabFailure(null);
            }
        }
    };

    private void queryInventory()
    {
        try
        {
            if (isIabInitialized())
            {
                List<String> timepassSkus = new ArrayList<>();
                timepassSkus.addAll(IAB_TIMEPASS_SKUS_TO_TIME.keySet());

                List<String> subscriptionSkus = new ArrayList<>();
                subscriptionSkus.add(IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU);
                subscriptionSkus.add(IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU);

                mIabHelper.queryInventoryAsync(
                        true,
                        timepassSkus,
                        subscriptionSkus,
                        m_iabQueryInventoryFinishedListener);
            }
        }
        catch (IllegalStateException ex)
        {
            handleIabFailure(null);
        }
        catch (IabHelper.IabAsyncInProgressException ex)
        {
            // Allow outstanding IAB request to finish.
        }
    }

    private void consumePurchases(List<Purchase> purchases)
    {
        try
        {
            if (isIabInitialized())
            {
                mIabHelper.consumeAsync(purchases, m_iabConsumeFinishedListener);
            }
        }
        catch (IllegalStateException ex)
        {
            handleIabFailure(null);
        }
        catch (IabHelper.IabAsyncInProgressException ex)
        {
            // Allow outstanding IAB request to finish.
        }
    }

    /**
     * Begin the flow for subscribing to premium access.
     */
    private void launchSubscriptionPurchaseFlow(String sku)
    {
        try
        {
            if (isIabInitialized())
            {
                mIabHelper.launchSubscriptionPurchaseFlow(
                        this,
                        sku,
                        IAB_REQUEST_CODE, m_iabPurchaseFinishedListener);
            }
        }
        catch (IllegalStateException ex)
        {
            handleIabFailure(null);
        }
        catch (IabHelper.IabAsyncInProgressException ex)
        {
            // Allow outstanding IAB request to finish.
        }
    }

    /**
     * Begin the flow for making a one-time purchase of time-limited premium access.
     */
    private void launchTimePassPurchaseFlow(String sku)
    {
        try
        {
            if (isIabInitialized())
            {
                mIabHelper.launchPurchaseFlow(this, sku,
                        IAB_REQUEST_CODE, m_iabPurchaseFinishedListener);
            }
        }
        catch (IllegalStateException ex)
        {
            handleIabFailure(null);
        }
        catch (IabHelper.IabAsyncInProgressException ex)
        {
            // Allow outstanding IAB request to finish.
        }
    }

    private void proceedWithValidSubscription(Purchase purchase)
    {
        psiphonAdManager.onSubscriptionStatus(PsiphonAdManager.SubscriptionStatus.SUBSCRIBER);
        psiCashFragment.onSubscriptionStatus(PsiphonAdManager.SubscriptionStatus.SUBSCRIBER);
        onSubscriptionStatus(PsiphonAdManager.SubscriptionStatus.SUBSCRIBER);
        Utils.setHasValidSubscription(this, true);
        this.m_retainedDataFragment.setCurrentPurchase(purchase);
        hidePsiCashTab();
        enableKinOptInCheckBox(false);

        // Pass the most current purchase data to the service if it is running so the tunnel has a
        // chance to update authorization and restart if the purchase is new.
        // NOTE: we assume there can be only one valid purchase and authorization at a time
        if (isTunnelConnected()) {
                startAndBindTunnelService();
        }

        // Auto-start on app first run
        if (shouldAutoStart()) {
            preventAutoStart();
            doStartUp();
        }
    }

    private void proceedWithoutValidSubscription()
    {
        psiphonAdManager.onSubscriptionStatus(PsiphonAdManager.SubscriptionStatus.NOT_SUBSCRIBER);
        psiCashFragment.onSubscriptionStatus(PsiphonAdManager.SubscriptionStatus.NOT_SUBSCRIBER);
        onSubscriptionStatus(PsiphonAdManager.SubscriptionStatus.NOT_SUBSCRIBER);
        Utils.setHasValidSubscription(this, false);
        currentRateLimitModeRelay.accept(RateLimitMode.AD_MODE_LIMITED);
        this.m_retainedDataFragment.setCurrentPurchase(null);
        showPsiCashTabIfHasValidToken();
        enableKinOptInCheckBox(true);
    }

    private void enableKinOptInCheckBox(boolean enable) {
        CheckBox checkBoxKinEnabled = findViewById(R.id.check_box_kin_enabled);
        if(enable) {
            checkBoxKinEnabled.setVisibility(View.VISIBLE);
        } else {
            checkBoxKinEnabled.setVisibility(View.GONE);
        }
    }

    private void setRateLimitUI(RateLimitMode rateLimitMode) {
        // Update UI elements showing the current speed.
        if (rateLimitMode == RateLimitMode.UNLIMITED_SUBSCRIPTION) {
            mRateLimitedText.setVisibility(View.GONE);
            mRateUnlimitedText.setVisibility(View.VISIBLE);
            mRateLimitSubscribeButton.setVisibility(View.GONE);
            mRateLimitedTextSection.setVisibility(View.VISIBLE);
        } else{
            if(rateLimitMode == RateLimitMode.AD_MODE_LIMITED) {
                mRateLimitedText.setText(getString(R.string.rate_limit_text_limited, 2));
            } else if (rateLimitMode == RateLimitMode.LIMITED_SUBSCRIPTION) {
                mRateLimitedText.setText(getString(R.string.rate_limit_text_limited, 5));
            } else if (rateLimitMode == RateLimitMode.SPEED_BOOST) {
                mRateLimitedText.setText(getString(R.string.rate_limit_text_speed_boost));
            }
            mRateLimitedText.setVisibility(View.VISIBLE);
            mRateUnlimitedText.setVisibility(View.GONE);
            mRateLimitSubscribeButton.setVisibility(View.VISIBLE);
            mRateLimitedTextSection.setVisibility(View.VISIBLE);
        }
    }

    // NOTE: result param may be null
    private void handleIabFailure(IabResult result)
    {
        psiphonAdManager.onSubscriptionStatus(PsiphonAdManager.SubscriptionStatus.SUBSCRIPTION_CHECK_FAILED);
        psiCashFragment.onSubscriptionStatus(PsiphonAdManager.SubscriptionStatus.SUBSCRIPTION_CHECK_FAILED);
        onSubscriptionStatus(PsiphonAdManager.SubscriptionStatus.SUBSCRIPTION_CHECK_FAILED);

        showPsiCashTabIfHasValidToken();
        enableKinOptInCheckBox(true);

        // try again next time
        deInitIab();

        if (result != null &&
            result.getResponse() == IabHelper.IABHELPER_USER_CANCELLED)
        {
            // do nothing, onResume() calls startIAB()
        }
        else
        {
            // Start the tunnel anyway, IAB will get checked again once the tunnel is connected
            if (shouldAutoStart())
            {
                preventAutoStart();
                doStartUp();
            }
        }
    }

    private void onSubscriptionStatus(PsiphonAdManager.SubscriptionStatus status) {
        subscriptionStatusBehaviorRelay.accept(status);
    }

    public void onRateLimitUpgradeButtonClick(View v) {
        if (!Utils.getHasValidSubscription(this)) {
            onSubscribeButtonClick(v);
            return;
        }

        try {
            if (isIabInitialized()) {
                // Replace any subscribed limited monthly subscription SKUs with the unlimited SKU
                mIabHelper.launchPurchaseFlow(
                        this,
                        IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU,
                        IabHelper.ITEM_TYPE_SUBS,
                        Arrays.asList(IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKUS),
                        IAB_REQUEST_CODE, m_iabPurchaseFinishedListener, "");
            }
        } catch (IllegalStateException ex) {
            handleIabFailure(null);
        } catch (IabHelper.IabAsyncInProgressException ex) {
            // Allow outstanding IAB request to finish.
        }
    }

    private final int PAYMENT_CHOOSER_ACTIVITY = 20001;

    @Override
    public void onSubscribeButtonClick(View v) {
        Utils.MyLog.g("StatusActivity::onSubscribeButtonClick");
        try {
            // User has clicked the Subscribe button, now let them choose the payment method.

            Intent feedbackIntent = new Intent(this, PaymentChooserActivity.class);

            // Pass price and SKU info to payment chooser activity.
            PaymentChooserActivity.SkuInfo skuInfo = new PaymentChooserActivity.SkuInfo();

            SkuDetails limitedSubscriptionSkuDetails = mInventory.getSkuDetails(IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU);
            skuInfo.mLimitedSubscriptionInfo.sku = limitedSubscriptionSkuDetails.getSku();
            skuInfo.mLimitedSubscriptionInfo.price = limitedSubscriptionSkuDetails.getPrice();
            skuInfo.mLimitedSubscriptionInfo.priceMicros = limitedSubscriptionSkuDetails.getPriceAmountMicros();
            skuInfo.mLimitedSubscriptionInfo.priceCurrency = limitedSubscriptionSkuDetails.getPriceCurrencyCode();
            // This is a subscription, so lifetime doesn't really apply. However, to keep things sane
            // we'll set it to 30 days.
            skuInfo.mLimitedSubscriptionInfo.lifetime = 30L * 24 * 60 * 60 * 1000;

            SkuDetails unlimitedSubscriptionSkuDetails = mInventory.getSkuDetails(IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU);
            skuInfo.mUnlimitedSubscriptionInfo.sku = unlimitedSubscriptionSkuDetails.getSku();
            skuInfo.mUnlimitedSubscriptionInfo.price = unlimitedSubscriptionSkuDetails.getPrice();
            skuInfo.mUnlimitedSubscriptionInfo.priceMicros = unlimitedSubscriptionSkuDetails.getPriceAmountMicros();
            skuInfo.mUnlimitedSubscriptionInfo.priceCurrency = unlimitedSubscriptionSkuDetails.getPriceCurrencyCode();
            // This is a subscription, so lifetime doesn't really apply. However, to keep things sane
            // we'll set it to 30 days.
            skuInfo.mUnlimitedSubscriptionInfo.lifetime = 30L * 24 * 60 * 60 * 1000;

            for (Map.Entry<String, Long> timepassSku : IAB_TIMEPASS_SKUS_TO_TIME.entrySet()) {
                SkuDetails timepassSkuDetails = mInventory.getSkuDetails(timepassSku.getKey());
                PaymentChooserActivity.SkuInfo.Info info = new PaymentChooserActivity.SkuInfo.Info();

                info.sku = timepassSkuDetails.getSku();
                info.price = timepassSkuDetails.getPrice();
                info.priceMicros = timepassSkuDetails.getPriceAmountMicros();
                info.priceCurrency = timepassSkuDetails.getPriceCurrencyCode();
                info.lifetime = timepassSku.getValue();

                skuInfo.mTimePassSkuToInfo.put(info.sku, info);
            }

            feedbackIntent.putExtra(PaymentChooserActivity.SKU_INFO_EXTRA, skuInfo.toString());

            startActivityForResult(feedbackIntent, PAYMENT_CHOOSER_ACTIVITY);
        } catch (NullPointerException e) {
            Utils.MyLog.g("StatusActivity::onSubscribeButtonClick error: " + e);
            // Show "Subscription options not available" toast.
            Toast toast = Toast.makeText(this, R.string.subscription_options_currently_not_available, Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        }
    }

    synchronized
    private void deInitIab()
    {
        mInventory = null;
        mIabHelperIsInitialized = false;
        if (mIabHelper != null)
        {
            try {
                mIabHelper.dispose();
            }
            catch (IabHelper.IabAsyncInProgressException ex)
            {
                // Nothing can help at this point. Continue to de-init.
            }

            mIabHelper = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (requestCode == IAB_REQUEST_CODE)
        {
            if (isIabInitialized())
            {
                mIabHelper.handleActivityResult(requestCode, resultCode, data);
            }
        }
        else if (requestCode == PAYMENT_CHOOSER_ACTIVITY)
        {
            if (resultCode == RESULT_OK)
            {
                int buyType = data.getIntExtra(PaymentChooserActivity.BUY_TYPE_EXTRA, -1);
                if (buyType == PaymentChooserActivity.BUY_SUBSCRIPTION)
                {
                    Utils.MyLog.g("StatusActivity::onActivityResult: PaymentChooserActivity: subscription");
                    String sku = data.getStringExtra(PaymentChooserActivity.SKU_INFO_EXTRA);
                    launchSubscriptionPurchaseFlow(sku);
                }
                else if (buyType == PaymentChooserActivity.BUY_TIMEPASS)
                {
                    Utils.MyLog.g("StatusActivity::onActivityResult: PaymentChooserActivity: time pass");
                    String sku = data.getStringExtra(PaymentChooserActivity.SKU_INFO_EXTRA);
                    launchTimePassPurchaseFlow(sku);
                }
            }
            else
            {
                Utils.MyLog.g("StatusActivity::onActivityResult: PaymentChooserActivity: canceled");
            }
        }
        else
        {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onVpnPromptCancelled() {
        showVpnAlertDialog(R.string.StatusActivity_VpnPromptCancelledTitle, R.string.StatusActivity_VpnPromptCancelledMessage);
    }

    @Override
    protected void configureServiceIntent(Intent intent) {
        super.configureServiceIntent(intent);
        // Pass Kin opt in state, if user is not subscribed treat as opt out.
        boolean kinOptInState = !Utils.getHasValidSubscription(getApplicationContext())
                && kinPermissionManager.isOptedIn(this);
        intent.putExtra(TunnelManager.KIN_OPT_IN_STATE_EXTRA, kinOptInState);
    }

    private void showVpnAlertDialog(int titleId, int messageId) {
        new AlertDialog.Builder(getContext())
                .setCancelable(true)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(titleId)
                .setMessage(messageId)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, android.content.Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE)) {
                    scheduleRunningTunnelServiceRestart();
                }
            }
        }
    };

    private void initializeKinOptInState() {
        if(initializeKinOptInStateDisposable != null && !initializeKinOptInStateDisposable.isDisposed()) {
            return;
        }
        initializeKinOptInStateDisposable = subscriptionStatusBehaviorRelay.concatMapSingle(subscriptionStatus -> {
            if (subscriptionStatus == PsiphonAdManager.SubscriptionStatus.SUBSCRIBER) {
                // Return 'any' object, the return value is ignored anyway.
                return Single.just(new Object());
            } else {
                // ask if the user agrees to kin if they haven't yet
                return kinPermissionManager.getUsersAgreementToKin(this)
                        .doOnSuccess(this::setKinState);
            }
        })
                .firstOrError()
                .ignoreElement()
                .subscribe();
    }

    private void setKinState(boolean optedIn) {
        CheckBox checkBoxKinEnabled = findViewById(R.id.check_box_kin_enabled);
        if (optedIn) {
            checkBoxKinEnabled.setChecked(true);
        } else {
            checkBoxKinEnabled.setChecked(false);
        }

        // Notify tunnel service too if it is running and the user is not subscribed
        if(isServiceRunning() && !Utils.getHasValidSubscription(getApplicationContext())) {
            Bundle data = new Bundle();
            data.putBoolean(TunnelManager.KIN_OPT_IN_STATE_EXTRA, optedIn);
            sendServiceMessage(TunnelManager.ClientToServiceMessage.KIN_OPT_IN_STATE.ordinal(), data);
        }
    }
}
