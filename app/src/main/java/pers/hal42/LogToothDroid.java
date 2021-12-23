package pers.hal42;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;
import java.util.Vector;

import pers.hal42.android.GriddedActivity;
import pers.hal42.android.Logger;
import pers.hal42.android.RfcommDevice;
import pers.hal42.android.ViewFormatter;


public class LogToothDroid extends GriddedActivity implements RfcommDevice.Snooper {
  private static final int REQUEST_ENABLE_BT = 1;   // a task id
  private final static String argsKey = "args";
  public static final Logger log = new Logger(LogToothDroid.class.getSimpleName());

  // program messages
  private ViewFormatter out;
  // comm data from the device.
  private ViewFormatter devOut;
  //random command code entry, will add button per FU commands once other code is working.
  private EditText cmdBox;
  private BluetoothAdapter hostRadio;
  //library objects for device
  private java.util.Vector<BluetoothDevice> remoteDevices;
  //our handlers for the remoteDevices entities:
  private Vector<RfcommDevice> sensors;
  //the active device
  private RfcommDevice sensor;


  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    log.i("onCreate");
    gridManager.setColumnCount(4);
    gridManager.addDisplay("qrelay: Remote Relay Controller");

    gridManager.addButton("save list", v -> saveCommandline());
    gridManager.addButton("list paired", v -> getCached());
    gridManager.addButton("discover", v -> doDiscovery());
    gridManager.addButton("!CLEAN", v -> forgetAll());

    cmdBox = gridManager.addTextEntry("/"); //until keyboard is tamed I will preload this.

    gridManager.addButton("<-send that", v -> sendCommand(cmdBox.getText().charAt(0)));
    gridManager.cursor.eol();//todo: add 'newline' method to GM so that we aren't creating then disposing of an object.

    out = new ViewFormatter(gridManager.addDisplay(""));
    devOut = new ViewFormatter(gridManager.addDisplay(""));

    makeAgent();//message receiver.

    try {
      hostRadio = BluetoothAdapter.getDefaultAdapter();
      if (hostRadio == null) {
        out.format("Bluetooth not supported on this device\nIf it existed you would be pushed to turn it on.\n");
        return;
      }

      if (!hostRadio.isEnabled()) {
        startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
      }
    } catch (Exception e) {
      log.e("enabling radio", e.getMessage());
    }

    SharedPreferences preferences = getPreferences(MODE_PRIVATE);
    Set<String> args = preferences.getStringSet(argsKey, null);
    int expected = args != null ? args.size() : 0;

    remoteDevices = new Vector<BluetoothDevice>(expected);
    sensors = new Vector<RfcommDevice>(expected);

    if (expected > 0) {
      out.printf("%d devices stored\n", expected);
      //create a logger for each commandline arg
      //null checked indirectly via initialization of expected
      for (String btAddress : args) {
        BluetoothDevice device = getDevice(btAddress);
        if (device != null) {
          addSensorFor(device, true);
          //todo: add button for removing this device from list.
        } else {
          log.e("No bonded device for stored address %s", btAddress);
          --expected;
        }
      }

      out.format("Configured devices:\n");
      listSensors();
    }
    gridManager.invalidate();
  }

  private void forgetAll() {
    disconnect();
    sensors.clear();
    remoteDevices.clear();
    //let background activity save the settings, so that we still have old ones if we crash real soon.
  }

  private void doDiscovery() {
    if (hostRadio != null) {
      hostRadio.startDiscovery();
    }
  }

  private void listSensors() {
    for (RfcommDevice sensor : sensors) {
      if (sensor == this.sensor) {//#same object
        out.putc('*');
      }
      out.format(sensor.addressString());
      out.endl();
    }
  }

  private BroadcastReceiver agent;

  private void makeAgent() {
    agent = new BroadcastReceiver() {
      public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (BluetoothDevice.ACTION_FOUND.equals(action)) {   // When discovery finds a device
          BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
          addDevice(device);
        } else if (android.bluetooth.BluetoothDevice.ACTION_UUID.equals(action)) {  //fetchUuidsWithSdp
          BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
          for (Parcelable uuid : intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)) { //isa ParcelUuid
            log.e(out.printf("sdp uuid %s\n%s\n", device.getAddress(), uuid == null ? "null uuid" : uuid.toString()));
          }
        }
      }
    };
    // Register the BroadcastReceiver
    IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
    filter.addAction(android.bluetooth.BluetoothDevice.ACTION_UUID);  //couldn't find any hint of regexp matching, couldn't find code for matching.
    registerReceiver(agent, filter); // Don't forget to unregister during onDestroy
  }


  @Override
  protected void onDestroy() {
    if (agent != null) {
      disconnect();//in hopes of being able to reconnect sooner
      unregisterReceiver(agent);
      agent = null;//handy for breakpoint.
    }
    super.onDestroy();
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
//super fn is trivial:--    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQUEST_ENABLE_BT) {   //0 is disabled, -1 for is enabled
      switch (resultCode) {
      case -1:
        log.d("Bluetooth now enabled.");
        break;
      case 0:
        log.d("User refused to enable Bluetooth.");
        break;
      default:
        log.d("user might have enabled bt:%d.", resultCode);
        break;
      }
    }
  }

  private void addDevice(final BluetoothDevice btDevice) {  //uniqued Vector.
    //todo: filter out ones that we don't want
    if (!remoteDevices.contains(btDevice)) {
      remoteDevices.addElement(btDevice);
      gridManager.addButton(btDevice.toString(), 2, new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          addSensorFor(btDevice, true);
        }
      });
      gridManager.addButton("Connect", 1, new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          connectOne(btDevice);
        }
      });
      gridManager.addButton("Forget!", 1, new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          out.format("Forgetting just one device Not Yet Implemented");
        }
      });
    }
  }

  private void connectOne(BluetoothDevice btDevice) {
    hostRadio.cancelDiscovery();  // Cancel discovery because it will slow down connection
    //disconnect any existing connection
    disconnect();
    //find sensor
    for (RfcommDevice asensor : sensors) {
      if (asensor.wraps(btDevice)) {
        if (asensor.connectLogger(this)) {
          sensor = asensor;
          out.printf("connected %d\n", sensor);
        } else {
          out.format("could not connect.\n");
        }
        return;
      }
    }
    out.format("could not find device to connect.\n");
  }

  /**
   * get device from list, @return null if no device found in list
   */
  public BluetoothDevice getDevice(String btAddress) {
    for (BluetoothDevice btDevice : remoteDevices) {
      if (btDevice.getAddress().equalsIgnoreCase(btAddress)) {
        return btDevice;
      }
    }
    return null;
  }

  public void getCached() {
    if (hostRadio == null) {
      log.e("Bluetooth not supported on this device");
      return;
    }
    log.i("This device: %s @%s", hostRadio.getName(), hostRadio.getAddress());
    Set<BluetoothDevice> pairedDevices = hostRadio.getBondedDevices();
    if (pairedDevices.size() > 0) {
      for (BluetoothDevice device : pairedDevices) {
        addDevice(device);
      }
    }
  }

  /** show service ID's for given @param device.
   * this helps with ensuring the device is the type that you are looking for. */

  public void listServices(BluetoothDevice device) {
    ParcelUuid[] uuids = device.getUuids();
    if (uuids != null) {
      out.format("Uuids recorded for {0}\n", device.getAddress());
      for (ParcelUuid uuid : uuids) {
        out.format("{0}\n", uuid.toString());
      }
    } else {
      out.format("No uuids recorded for {0}\n", device.getAddress());
    }
  } /* listServices */



  /** emit a command byte to the device */
  private void sendCommand(int keystroke) {
    if(sensor!=null) {
      sensor.sendCommand(keystroke);
    }
  }

  /** safely disconnect from a sensor, if one is connected */
  private void disconnect() {
    if (sensor != null) {
      sensor.disconnect();
      sensor = null;
    }
  }

  /**
   * @param device which device we are going to track
   * @param listServices whether to fetch service info and display it
   * @return device wrapper for given device.
   */
  private RfcommDevice addSensorFor(BluetoothDevice device, boolean listServices) {
    RfcommDevice sensor = new RfcommDevice(new AndroidSensorConnection(device));
    sensors.add(sensor);
    if (listServices) {
      listServices(device);
      device.fetchUuidsWithSdp();
    }
    return sensor;
  }

  private void saveCommandline() {
    Set<String> args = new HashSet<String>(sensors.size());
    for (RfcommDevice sensor : sensors) {
      if (sensor != null) {
        args.add(sensor.addressString());
      }
    }
    SharedPreferences preferences = getPreferences(MODE_PRIVATE);
    SharedPreferences.Editor editor = preferences.edit();
    editor.putStringSet(argsKey, args);
    editor.apply();//critical magic.
  }

  @Override
  protected void onPause() {
    super.onPause();
    saveCommandline();
  }

  @Override
  public boolean updated(int read) {
    devOut.putc(read);
    return false;
  }

}

