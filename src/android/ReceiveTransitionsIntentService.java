package com.cowbell.cordova.geofence;

import java.util.ArrayList;
import java.util.List;

import android.app.IntentService;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.protocol.HTTP;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;
import android.os.AsyncTask;
import android.os.Build;
import java.util.Iterator;
import android.os.SystemClock;
import com.shobshop.shobshopapp.R;

import java.util.ArrayList;
import java.util.List;
import android.location.Location;

public class ReceiveTransitionsIntentService extends IntentService {
    protected static final String GeofenceTransitionIntent = "com.cowbell.cordova.geofence.TRANSITION";
    protected BeepHelper beepHelper;
    protected GeoNotificationNotifier notifier;
    protected GeoNotificationStore store;

    /**
     * Sets an identifier for the service
     */
    public ReceiveTransitionsIntentService() {
        super("ReceiveTransitionsIntentService");
        beepHelper = new BeepHelper();
        store = new GeoNotificationStore(this);
        Logger.setLogger(new Logger(GeofencePlugin.TAG, this, false));
    }

    private boolean isInsideGeofence(GeoNotification geoNotification, Location location){
        int radius = geoNotification.radius;
        Location geoNotiLocation = new Location("dummyLocation");
        geoNotiLocation.setLatitude(geoNotification.latitude);
        geoNotiLocation.setLongitude(geoNotification.longitude);
        return geoNotiLocation.distanceTo(location) <= radius;
    }

    /**
     * Handles incoming intents
     *
     * @param intent
     *            The Intent sent by Location Services. This Intent is provided
     *            to Location Services (inside a PendingIntent) when you call
     *            addGeofences()
     */
    @Override
    protected void onHandleIntent(Intent intent) {
        Logger logger = Logger.getLogger();
        logger.log(Log.DEBUG, "ReceiveTransitionsIntentService - onHandleIntent");
        Intent broadcastIntent = new Intent(GeofenceTransitionIntent);
        notifier = new GeoNotificationNotifier(
                (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE),
                this
        );

        // First check for errors
        GeofencingEvent geofencingEvent = GeofencingEvent.fromIntent(intent);
        if (geofencingEvent.hasError()) {
            // Get the error code with a static method
            int errorCode = geofencingEvent.getErrorCode();
            String error = "Location Services error: " + Integer.toString(errorCode);
            // Log the error
            logger.log(Log.ERROR, error);
            broadcastIntent.putExtra("error", error);
        } else {
            Location triggeringLocation = geofencingEvent.getTriggeringLocation();
            // Get the type of transition (entry or exit)
            int transitionType = geofencingEvent.getGeofenceTransition();
            if ((transitionType == Geofence.GEOFENCE_TRANSITION_ENTER)
                    || (transitionType == Geofence.GEOFENCE_TRANSITION_EXIT)) {
                List<Geofence> triggerList = geofencingEvent.getTriggeringGeofences();
                logger.log(Log.DEBUG, "Geofence transition detected : "+transitionType);
                List<GeoNotification> geoNotifications = new ArrayList<GeoNotification>();
                for (Geofence fence : triggerList) {
                    String fenceId = fence.getRequestId();
                    GeoNotification geoNotification = store
                            .getGeoNotification(fenceId);

                    //Notify server if transition type is "Exit" or actual "Enter"

                    if (geoNotification != null && (
                                transitionType == Geofence.GEOFENCE_TRANSITION_EXIT || (
                                    transitionType == Geofence.GEOFENCE_TRANSITION_ENTER && isInsideGeofence(geoNotification, triggeringLocation)
                        ))) {
                        String transitionText = "";

                        geoNotification.transitionType = transitionType;

                        if(geoNotification.transitionType == Geofence.GEOFENCE_TRANSITION_ENTER)
                            transitionText = "Enter";
                        else if(geoNotification.transitionType == Geofence.GEOFENCE_TRANSITION_EXIT)
                            transitionText = "Exit";

                        //Attach triggering location
                        geoNotification.latitude = triggeringLocation.getLatitude();
                        geoNotification.longitude = triggeringLocation.getLongitude();

                        //Submit triggered geofence to server

                        PostGeofenceTask task = new ReceiveTransitionsIntentService.PostGeofenceTask(geoNotification);
                        logger.log(Log.DEBUG, "beforeexecute " +  task.getStatus());

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
                            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                        else
                            task.execute();
                        logger.log(Log.DEBUG, "afterexecute " +  task.getStatus());
                        geoNotifications.add(geoNotification);
                    }
                }

                if (geoNotifications.size() > 0) {
                    broadcastIntent.putExtra("transitionData", Gson.get().toJson(geoNotifications));
                    GeofencePlugin.onTransitionReceived(geoNotifications);
                }
            } else {
                String error = "Geofence transition error: " + transitionType;
                logger.log(Log.ERROR, error);
                broadcastIntent.putExtra("error", error);
            }
        }
        sendBroadcast(broadcastIntent);
    }    

    protected boolean notifyServer(GeoNotification geoNotification){
        Logger logger = Logger.getLogger();
        long lastUpdateTime = 0l;
        // String url = "http://shobshopdev.herokuapp.com/location";
        String url = getString(R.string.api_home)+getString(R.string.path_geofence_event);
        JSONObject params;
        JSONObject headers;
        try {
            lastUpdateTime = SystemClock.elapsedRealtime();
            DefaultHttpClient httpClient = new DefaultHttpClient();
            HttpPost request = new HttpPost(url);

            params = new JSONObject();
            headers = new JSONObject();
            String transitionText = "";
            if(geoNotification.transitionType == Geofence.GEOFENCE_TRANSITION_ENTER)
                transitionText = "enter";
            else if(geoNotification.transitionType == Geofence.GEOFENCE_TRANSITION_EXIT)
                transitionText = "exit";
            params.put("location", geoNotification.id);
            params.put("latitude", geoNotification.latitude);
            params.put("longitude", geoNotification.longitude);
            params.put("transitionType", transitionText);
            SharedPreferenceHelper sHelper = new SharedPreferenceHelper(this);
            String userId = sHelper.getString(SharedPreferenceHelper.KEY_USERID,null);
            String deviceId = sHelper.getString(SharedPreferenceHelper.KEY_DEVICEID,null);
            params.put("user", userId);
            if(userId == null){     //Send deviceId only if not logging in
                params.put("device", sHelper.getString(SharedPreferenceHelper.KEY_DEVICEID,null));
            }
            logger.log(Log.DEBUG, "Params = "+params.toString());

            StringEntity se = new StringEntity(params.toString(), HTTP.UTF_8);
            request.setEntity(se);
            request.setHeader("Accept", "application/json");
            request.setHeader("Content-type", "application/json");

            Iterator<String> headkeys = headers.keys();
            while( headkeys.hasNext() ){
                String headkey = headkeys.next();
                if(headkey != null) {
                    logger.log(Log.DEBUG, "Adding Header: " + headkey + " : " + (String)headers.getString(headkey));
                    request.setHeader(headkey, (String)headers.getString(headkey));
                }
            }
            logger.log(Log.DEBUG, "Posting to " + request.getURI().toString());
            HttpResponse response = httpClient.execute(request);
            logger.log(Log.DEBUG, "Response received: " + response.getStatusLine());
            if (response.getStatusLine().getStatusCode() == 201) {  //201 - Created
                return true;
            } else {
                return false;
            }
        } catch (Throwable e) {
            logger.log(Log.WARN, "Exception posting geofence: " + e);
            e.printStackTrace();
            return false;
        }
    }
    private class PostGeofenceTask extends AsyncTask<Object, Integer, Boolean> {
        Logger logger = Logger.getLogger();
        private GeoNotification geoNotification;
        PostGeofenceTask(GeoNotification geoNotification){
            this.geoNotification = geoNotification;
        }

        @Override
        protected Boolean doInBackground(Object...objects) {
            logger.log(Log.DEBUG, "Executing PostGeofenceTask#doInBackground");
            return notifyServer(geoNotification);
        }
        @Override
        protected void onPostExecute(Boolean result) {
            logger.log(Log.DEBUG, "PostGeofenceTask#onPostExecture");
            /*String transitionText = "";
            if(geoNotification.transitionType == Geofence.GEOFENCE_TRANSITION_ENTER)
                transitionText = "Enter";
            else if(geoNotification.transitionType == Geofence.GEOFENCE_TRANSITION_EXIT)
                transitionText = "Exit";
            //Notify result
            Notification noti = new Notification();
            noti.id = 2;
            if(result){
                noti.title = "Submit geofence successfully";
            }
            else{
                noti.title = "Submit geofence failed";
            }
            noti.text = transitionText+" "+geoNotification.notification.title;
            noti.openAppOnClick = true;
            noti.data = null;
            notifier.notify(noti,true);*/
        }
    }
}