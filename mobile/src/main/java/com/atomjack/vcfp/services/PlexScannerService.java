package com.atomjack.vcfp.services;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;

import com.atomjack.vcfp.GDMService;
import com.atomjack.shared.Logger;
import com.atomjack.vcfp.PlexHeaders;
import com.atomjack.shared.Preferences;
import com.atomjack.vcfp.VoiceControlForPlexApplication;
import com.atomjack.vcfp.model.Device;
import com.atomjack.vcfp.model.MediaContainer;
import com.atomjack.vcfp.model.PlexServer;
import com.google.gson.Gson;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import us.nineworlds.serenity.GDMReceiver;

public class PlexScannerService extends Service {
  public static final String ACTION_SCAN_SERVERS = "com.atomjack.vcfp.plexscannerservice.action_scan_servers";
  public static final String ACTION_SCAN_CLIENTS = "com.atomjack.vcfp.plexscannerservice.action_scan_clients";
  public static final String ACTION_SERVER_SCAN_FINISHED = "com.atomjack.vcfp.plexscannerservice.action_server_scan_finished";
  public static final String REMOTE_SERVER_SCAN_UNAUTHORIZED = "com.atomjack.vcfp.plexscannerservice.remote_server_scan_unauthorized";
//  public static final String ACTION_LOCAL_SERVER_SCAN_FINISHED = "com.atomjack.vcfp.plexscannerservice.action_local_server_scan_finished";
  public static final String ACTION_CLIENT_SCAN_FINISHED = "com.atomjack.vcfp.plexscannerservice.action_client_scan_finished";
  public static final String SCAN_TYPE = "com.atomjack.vcfp.plexscannerservice.scan_type";
  public static final String CLASS = "com.atomjack.vcfp.plexscannerservice.class";
  public static final String CONNECT_TO_CLIENT = "com.atomjack.vcfp.plexscannerservice.connect_to_client";

  private Class callingClass;

  private BroadcastReceiver gdmReceiver;

  private boolean localServerScanFinished = false;
  private boolean remoteServerScanFinished = false;

  private static AsyncHttpClient client = new AsyncHttpClient();
  private static Serializer serial = new Persister();

  private static boolean cancel = false;

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent.getAction();
    if(action != null) {
      Logger.d("Action: %s", action);
      if(action.equals(ACTION_SCAN_SERVERS)) {
        setClass(intent);
        scanForServers();
      } else if(action.equals(ACTION_SCAN_CLIENTS)) {
        setClass(intent);
        scanForClients(intent.getBooleanExtra(com.atomjack.shared.Intent.EXTRA_CONNECT_TO_CLIENT, false));
      } else if(action.equals(ACTION_SERVER_SCAN_FINISHED)) {
        Logger.d("local server scan finished");
        Logger.d("is logged in: %s", isLoggedIn());
        localServerScanFinished = true;
        if(remoteServerScanFinished || !isLoggedIn()) {
          onServerScanFinished();
        }
      } else if(action.equals(ACTION_CLIENT_SCAN_FINISHED)) {
        onClientScanFinished();
      }
    }
    return Service.START_NOT_STICKY;
  }

  private void setClass(Intent intent) {
    callingClass = (Class) intent.getSerializableExtra(CLASS);
  }

  private void onServerScanFinished() {
    onScanFinished(ACTION_SERVER_SCAN_FINISHED);
  }

  private void onClientScanFinished() {
    onScanFinished(ACTION_CLIENT_SCAN_FINISHED);
  }

  private void onScanFinished(String type) {
    onScanFinished(type, null);
  }
  private void onScanFinished(String type, String extra) {
    Intent intent = new Intent(this, callingClass);
    intent.setAction(type);
    intent.putExtra(REMOTE_SERVER_SCAN_UNAUTHORIZED, extra != null && extra.equals(REMOTE_SERVER_SCAN_UNAUTHORIZED));
    intent.addFlags(Intent.FLAG_FROM_BACKGROUND);
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    if (callingClass.getSuperclass() == Service.class)
      startService(intent);
    else
      startActivity(intent);
  }

  private boolean isLoggedIn() {
    return VoiceControlForPlexApplication.getInstance().prefs.getString(Preferences.AUTHENTICATION_TOKEN) != null;
  }

  private void scanForServers() {
    VoiceControlForPlexApplication.servers = new ConcurrentHashMap<String, PlexServer>();
    if(isLoggedIn()) {
      refreshResources(VoiceControlForPlexApplication.getInstance().prefs.getString(Preferences.AUTHENTICATION_TOKEN), new RefreshResourcesResponseHandler() {
        @Override
        public void onSuccess() {
          remoteServerScanFinished = true;
          if (localServerScanFinished)
            onServerScanFinished();
        }

        @Override
        public void onFailure(int statusCode) {
          Logger.d("[PlexScannerService] failure: %d", statusCode);
          if (statusCode == 401) { // Unauthorized
            // REMOTE_SERVER_SCAN_UNAUTHORIZED
            onScanFinished(ACTION_SERVER_SCAN_FINISHED, REMOTE_SERVER_SCAN_UNAUTHORIZED);
          }
        }
      });
    }
    Logger.d("Doing local scan for servers");
    Intent mServiceIntent = new Intent(this, GDMService.class);
    mServiceIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
//    mServiceIntent.putExtra(com.atomjack.shared.Intent.EXTRA_SILENT, silent);
    mServiceIntent.putExtra(com.atomjack.shared.Intent.EXTRA_CLASS, PlexScannerService.class);
    mServiceIntent.putExtra(com.atomjack.shared.Intent.SCAN_TYPE, com.atomjack.shared.Intent.SCAN_TYPE_SERVER);
    startService(mServiceIntent);

  }

  public void scanForClients() {
    scanForClients(false);
  }

  private void scanForClients(boolean connectToClient) {
    Intent mServiceIntent = new Intent(this, GDMService.class);
    mServiceIntent.putExtra(GDMService.PORT, 32412); // Port for clients
    mServiceIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    mServiceIntent.putExtra(com.atomjack.shared.Intent.EXTRA_CLASS, callingClass);
    mServiceIntent.putExtra(com.atomjack.shared.Intent.EXTRA_CONNECT_TO_CLIENT, connectToClient);
    mServiceIntent.putExtra(com.atomjack.shared.Intent.SCAN_TYPE, com.atomjack.shared.Intent.SCAN_TYPE_CLIENT);
    startService(mServiceIntent);
    VoiceControlForPlexApplication.hasDoneClientScan = true;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    gdmReceiver = new GDMReceiver();
    IntentFilter filters = new IntentFilter();
    filters.addAction(GDMService.MSG_RECEIVED);
    filters.addAction(GDMService.SOCKET_CLOSED);
    filters.addAction(GDMReceiver.ACTION_CANCEL);
    LocalBroadcastManager.getInstance(this).registerReceiver(gdmReceiver,
            filters);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    if(gdmReceiver != null) {
      LocalBroadcastManager.getInstance(this).unregisterReceiver(gdmReceiver);
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  // Remote Scan methods
  public interface RefreshResourcesResponseHandler {
    void onSuccess();
    void onFailure(int statusCode);
  }

  public static void refreshResources(String authToken, final RefreshResourcesResponseHandler responseHandler) {
    refreshResources(authToken, responseHandler, false);
  }

  public static void refreshResources(String authToken) {
    refreshResources(authToken, null, true);
  }

  public static void refreshResources(String authToken, final RefreshResourcesResponseHandler responseHandler, boolean silent) {
    cancel = false;
    VoiceControlForPlexApplication.hasDoneClientScan = true;
//		VoiceControlForPlexApplication.servers = new ConcurrentHashMap<String, PlexServer>();
    String url = String.format("https://plex.tv/pms/resources?%s=%s", PlexHeaders.XPlexToken, authToken);
    Logger.d("Fetching %s", url);
    client.get(url, new RequestParams(), new AsyncHttpResponseHandler() {
      @Override
      public void onSuccess(int statusCode, org.apache.http.Header[] headers, byte[] responseBody) {
        try {
          if(cancel) {
            cancel = false;
            return;
          }
          MediaContainer mediaContainer = new MediaContainer();

          try {
            mediaContainer = serial.read(MediaContainer.class, new String(responseBody, "UTF-8"));
          } catch (Resources.NotFoundException e) {
            e.printStackTrace();
          } catch (Exception e) {
            e.printStackTrace();
          }
          Logger.d("got %d devices", mediaContainer.devices.size());

          List<PlexServer> servers = new ArrayList<PlexServer>();
          for(final Device device : mediaContainer.devices) {
            if(device.lastSeenAt < System.currentTimeMillis()/1000 - (60*60*24))
              continue;
            if(device.provides.contains("server")) {

              PlexServer server = PlexServer.fromDevice(device);
              Logger.d("Device %s is a server, has %d connections", server.name, server.connections.size());
              servers.add(server);
            } else if(device.provides.contains("player")) {
              Logger.d("Device %s is a player", device.name);
            }
          }

          final int[] serversScanned = new int[1];
          serversScanned[0] = 0;
          final int numServers = servers.size();
          for(final PlexServer server : servers) {
            VoiceControlForPlexApplication.addPlexServer(server, new Runnable() {
              @Override
              public void run() {
                Logger.d("Done scanning %s", server.name);
                serversScanned[0]++;
                Logger.d("%d out of %d servers scanned", serversScanned[0], numServers);
                if(serversScanned[0] >= numServers && responseHandler != null)
                  responseHandler.onSuccess();
              }
            });
          }





        } catch (Exception e) {
          if(responseHandler != null)
            responseHandler.onFailure(0);
        }
      }

      @Override
      public void onFailure(int statusCode, org.apache.http.Header[] headers, byte[] responseBody, java.lang.Throwable error) {
        Logger.d("Failure getting resources: %d", statusCode);
        if(cancel) {
          cancel = false;
          return;
        }
        error.printStackTrace();
        if(responseHandler != null)
          responseHandler.onFailure(statusCode);
      }
    });
  }

}
