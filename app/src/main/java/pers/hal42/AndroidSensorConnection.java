package pers.hal42;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;


import pers.hal42.android.Logger;
import pers.hal42.android.RfcommDevice;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Copyright (C) by andyh created on 10/18/12 at 12:17 PM
 */
public class AndroidSensorConnection implements RfcommDevice.Connection, Runnable {

  private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");//acquired from listSensors in LogToothDroid
  private final BluetoothDevice device;
  private BluetoothSocket socket;
  public final Logger log;
  private Runnable sensor; //backlink to wrapper

  public AndroidSensorConnection(BluetoothDevice device) {
    this.device = device;
    log = new Logger(this.getClass().getSimpleName() + "/" + device.getAddress());

    try {
      socket = device.createRfcommSocketToServiceRecord(MY_UUID);
    } catch (IOException e) {
      log.e("failed to create socket");
      socket = null;
    }
  }

  public void run() {
    if (socket == null) {
      return;
    }

    try {
      socket.connect(); // This will block until it succeeds or throws an exception
      sensor.run();
    } catch (IOException connectException) {
      log.e(connectException.getMessage());
    }
    close();
  }

  /**
   * Will cancel an in-progress connection, and close the socket
   */
  public void close() {
    if (socket == null) {
      return;
    }

    try {
      socket.close();
    } catch (IOException e) {
      log.e(e.getMessage());
    }
  }


  public boolean open(Runnable sensor) {
    if (socket != null) {
      this.sensor = sensor;
      Thread worker = new Thread(this, device.getAddress());
      worker.start();  //thread dies when either the connect excepts or the sensor's runnable quits.
      return true;
    } else {
      return false;
    }
  }

  @Override
  public InputStream reader() throws IOException {
    return socket.getInputStream();
  }

  @Override
  public OutputStream writer() throws IOException {
    return socket.getOutputStream();
  }

  @Override
  public String addressString() {
    return device.getAddress();
  }

}

