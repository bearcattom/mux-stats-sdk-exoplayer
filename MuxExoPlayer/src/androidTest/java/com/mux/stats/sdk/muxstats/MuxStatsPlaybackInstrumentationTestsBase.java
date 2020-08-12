package com.mux.stats.sdk.muxstats;

import android.util.Log;
import android.view.View;

import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.ui.PlayerControlView;
import com.google.android.exoplayer2.ui.PlayerView;
import com.mux.stats.sdk.muxstats.mockup.Event;
import com.mux.stats.sdk.muxstats.mockup.MockNetworkRequest;
import com.mux.stats.sdk.muxstats.mockup.http.SimpleHTTPServer;
import com.mux.stats.sdk.muxstats.ui.SimplePlayerTestActivity;

import org.json.JSONException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public abstract class MuxStatsPlaybackInstrumentationTestsBase {

    public static final String TAG = "playbackTest";

    static final int PLAY_PERIOD_IN_MS = 10000;
    static final int PAUSE_PERIOD_IN_MS = 3000;
    static final int EVENT_MAX_TIME_DIFF_MS = 100;

    @Rule
    public ActivityTestRule<SimplePlayerTestActivity> activityTestRule;

    PlayerControlView controlView;
    View pauseButton;
    View playButton;

    protected SimplePlayerTestActivity testActivity;
    protected SimpleHTTPServer httpServer;
    protected PlayerView pView;
    protected MediaSource testMediaSource;
    protected MockNetworkRequest networkRequest;
    // 2 mega bits per second

    protected int networkJamPeriodInMs = 10000;
    // This is the number of times the network bandwidth will be reduced,
    // not constantly but each 10 ms a random number between 2 and factor will divide
    // the regular amount of bytes to send
    protected int networkJamFactor = 4;
    protected int bandwidthLimitInBitsPerSecond = 1500000;
    protected int runHttpServerOnPort = 5000;


    @Before
    public void init(){
        try {
            httpServer = new SimpleHTTPServer(runHttpServerOnPort, bandwidthLimitInBitsPerSecond);
        } catch (IOException e) {
            e.printStackTrace();
            // Failed to start server
            assertTrue(false);
        }
        activityTestRule = new ActivityTestRule<SimplePlayerTestActivity>(
                SimplePlayerTestActivity.class,
                true,
                false);
        activityTestRule.launchActivity(null);
        testActivity = activityTestRule.getActivity();
        pView = testActivity.getPlayerView();
        testMediaSource = testActivity.getTestMediaSource();
        networkRequest = testActivity.getMockNetwork();
    }

    /*
     * Check if given events are dispatched in correct order and timestamp
     */
    private void checkEvents(ArrayList<Event> eventsOrder, MockNetworkRequest networkRequest) throws JSONException {
        int lookingForEventAtIndex = 0;

        for (int i = 0; i < networkRequest.getNumberOfReceivedEvents(); i++ ) {
            String receivedEventName = networkRequest.getReceivedEventName(i);
            if (receivedEventName.equals(eventsOrder.get(lookingForEventAtIndex).getName())) {
                lookingForEventAtIndex++;
            }
            if (lookingForEventAtIndex >= eventsOrder.size()) {
                return;
            }
        }

        ArrayList<String> eventsOrderNames = new ArrayList<>();
        for (int i = 0; i < eventsOrder.size(); i++) {
            eventsOrderNames.add(eventsOrder.get(i).getName());
        }

        String failMessage = "Received events not in a correct order:\n";
        failMessage += "Expected: " + eventsOrderNames + " \n";
        failMessage += "Received: " + networkRequest.getReceivedEventNames();
        fail(failMessage);
    }

    /*
     * According to the self validation guid: https://docs.google.com/document/d/1FU_09N3Cg9xfh784edBJpgg3YVhzBA6-bd5XHLK7IK4/edit#
     * We are implementing vod playback scenario.
     */
    public void testVodPlayback() {
        try {
            testActivity.waitForPlaybackToStart();

            // Init player controlls
            controlView = pView.findViewById(R.id.exo_controller);
            if (controlView != null) {
                pauseButton = controlView.findViewById(R.id.exo_pause);
                playButton = controlView.findViewById(R.id.exo_play);
            }

            ArrayList<Event> expectedEvents = new ArrayList<>();

            // play x seconds
            Thread.sleep(PLAY_PERIOD_IN_MS);
            // check player init events
            expectedEvents.add(new Event("playerready"));
            expectedEvents.add(new Event("viewstart"));
            expectedEvents.add(new Event("play"));
            expectedEvents.add(new Event("playing"));

            pausePlayer();

            // check player pause event
            expectedEvents.add(new Event("pause"));

            Thread.sleep(PAUSE_PERIOD_IN_MS);
            // Resume video

            resumePlayer();

            /// check player play event
            expectedEvents.add(new Event("play"));
            expectedEvents.add(new Event("playing"));

            // Play another x seconds
            Thread.sleep(PLAY_PERIOD_IN_MS);

            // Seek backward
            testActivity.runOnUiThread(new Runnable(){
                public void run() {
                    long currentPlaybackPosition = pView.getPlayer().getCurrentPosition();
                    pView.getPlayer().seekTo(currentPlaybackPosition/2);
                }
            });

            // Play another x seconds
            Thread.sleep(PLAY_PERIOD_IN_MS);

            // check player seek event
            expectedEvents.add(new Event("pause"));
            expectedEvents.add(new Event("seeking"));
            expectedEvents.add(new Event("seeked"));
            expectedEvents.add(new Event("playing"));

            // seek forward in the video
            testActivity.runOnUiThread(new Runnable(){
                public void run() {
                    long currentPlaybackPosition = pView.getPlayer()
                            .getCurrentPosition();
                    long videoDuration = pView.getPlayer().getDuration();
                    long seekToInFuture = currentPlaybackPosition + ((videoDuration - currentPlaybackPosition) / 2);
                    pView.getPlayer().seekTo(seekToInFuture);
                }
            });

            // Play another x seconds
            Thread.sleep(PLAY_PERIOD_IN_MS);

            // check player seek event
            expectedEvents.add(new Event("pause"));
            expectedEvents.add(new Event("seeking"));
            expectedEvents.add(new Event("seeked"));
            expectedEvents.add(new Event("playing"));

            // Exit the player with back button
            testActivity.runOnUiThread(new Runnable(){
                public void run() {
                    testActivity.onBackPressed();
                }
            });

            testActivity.waitForActivityToClose();
            Log.w(TAG, "See what event should be dispatched on view closed !!!");
            // TODO check player end event
            checkEvents(expectedEvents, networkRequest);
        } catch (InterruptedException e) {
            e.printStackTrace();
            // fail test
            fail();
        } catch (JSONException e) {
            e.printStackTrace();
            fail();
        }
        Log.e(TAG, "All done !!!");
    }

    void testRebuffering() {
        try {
            testActivity.waitForPlaybackToStart();
            // play x seconds
            Thread.sleep(PLAY_PERIOD_IN_MS);
            // Jam network for 2 seconds, we expect 2 seconds of rebuffering
            testActivity.runOnUiThread(new Runnable(){
                public void run() {
                    long bufferPosition = pView.getPlayer().getBufferedPosition();
                    long currentPosition = pView.getPlayer().getCurrentPosition();
                    long bufferedTime = bufferPosition - currentPosition;
                    Log.w(TAG, "Starting to jam network for:" + networkJamPeriodInMs +
                            ", current time on buffer: " + bufferedTime);
                    httpServer.jamNetwork(networkJamPeriodInMs, networkJamFactor);
                }
            });

            // play x seconds
//            Thread.sleep(PLAY_PERIOD_IN_MS);
            testActivity.waitForPlaybackToFinish();
            // TODO check rebuffering events
//        } catch (JSONException e) {
//            e.printStackTrace();
//            fail();
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail();
        }
    }


    public void pausePlayer() {
        // Pause video
        testActivity.runOnUiThread(new Runnable(){
            public void run()
            {
                if (pauseButton != null) {
                    pauseButton.performClick();
                } else {
                    pView.getPlayer().stop();
                }
            }
        });
    }

    public void resumePlayer() {
        testActivity.runOnUiThread(new Runnable(){
            public void run() {
                if (playButton != null) {
                    playButton.performClick();
                } else {
                    SimpleExoPlayer player = ((SimpleExoPlayer)pView.getPlayer());
                    player.prepare(testMediaSource, false, false);
                }
            }
        });
    }
}