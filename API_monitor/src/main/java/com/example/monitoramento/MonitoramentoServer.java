package com.example.monitoramento;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.example.monitoramento.controller.Monitor;

public class MonitoramentoServer {
    private static final Lock pingLock = new ReentrantLock();
   

    public static void main(String[] args) {
        new MonitoramentoServer().iniciarMonitoramento();
    }

    public void iniciarMonitoramento() {
        new Thread(this::iniciarPingAndWrite).start();
       
    }

    private void iniciarPingAndWrite() {
        Monitor controllerPing = new Monitor();
        while (true) {
            try {
                pingLock.lock();
                controllerPing.startPingHosts();
                Thread.sleep(10 * 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                pingLock.unlock();
            }
        }
    }


}
