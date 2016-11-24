/**
 * Copyright (C) 2009-2013 Couchbase, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALING
 * IN THE SOFTWARE.
 */

package com.couchbase.roadrunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.couchbase.roadrunner.workloads.DocumentGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.CouchbaseCluster;
import com.google.common.base.Stopwatch;

/**
 * The WorkloadDispatcher is responsible for initializing the Clients, their
 * corresponding workers and initializing the workload.
 */
final class WorkloadDispatcher {

  /** Configure a reusable logger. */
  static final Logger LOGGER =
    LoggerFactory.getLogger(WorkloadDispatcher.class.getName());

  /** The global configuration object. */
  private final GlobalConfig config;
  private final Cluster cluster;
  private DocumentGenerator documentGenerator;

  /** Links to the clientHandlers for each CouchabaseClient. */
  private List<ClientHandler> clientHandlers;

  Map<String, List<Stopwatch>> mergedMeasures;

  /**
   * Create the WorkloadDispatcher object.
   *
   * @param config The global configuration object with all settings.
   */
  public WorkloadDispatcher(final GlobalConfig config) {
    this.config = config;
    this.cluster = CouchbaseCluster.create(config.getNodes());
    this.clientHandlers = new ArrayList<ClientHandler>();
    this.mergedMeasures = new HashMap<String, List<Stopwatch>>();
    this.documentGenerator = new DocumentGenerator(config.getClassName());
  }

  /**
   * Initialize and run the ClientHandlers.
   */
  public void init() throws Exception {
    try {
      int offset = 0;
      int docsPerHandler = (int)Math.floor(
          config.getNumDocs()/config.getNumClients());
      for (int i=0;i<config.getNumClients();i++) {
        clientHandlers.add(new ClientHandler(config, "ClientHandler-"+(i+1), docsPerHandler, offset, this.documentGenerator));
        offset = offset + docsPerHandler;
      }
    } catch (Exception e) {
      cluster.disconnect();
      throw e;
    }
  }

  /**
   * Distribute and run the workload against the ClientHandlers.
   */
  public void dispatchWorkload() throws Exception {
    try {
      for(ClientHandler handler : clientHandlers) {
        handler.executeWorkload(this.documentGenerator);
      }
      while (getTotalOps() != config.getNumDocs()) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ex) {

        }
      }

      for(ClientHandler handler : clientHandlers) {
        handler.cleanup();
      }
    } finally {
      cluster.disconnect();
    }
  }

  public void prepareMeasures() {
    storeMeasures();
  }

  private void storeMeasures() {
    for(ClientHandler handler : clientHandlers) {
      Map<String, List<Stopwatch>> measures = handler.getMeasures();
      for (Map.Entry<String, List<Stopwatch>> entry : measures.entrySet()) {
        if(mergedMeasures.containsKey(entry.getKey())) {
          mergedMeasures.get(entry.getKey()).addAll(entry.getValue());
        } else {
          mergedMeasures.put(entry.getKey(), entry.getValue());
        }
      }
    }
  }

  public Map<String, List<Stopwatch>> getMeasures() {
    return mergedMeasures;
  }

  public long getTotalOps() {
    long totalOps = 0;
    for (ClientHandler handler : clientHandlers) {
      totalOps += handler.getTotalOps();
    }
    return totalOps;
  }

  public long getMeasuredOps() {
    long measuredOps = 0;
    for (ClientHandler handler : clientHandlers) {
      measuredOps += handler.getMeasuredOps();
    }
    return measuredOps;
  }

  public List<Stopwatch> getThreadElapsed() {
    List<Stopwatch> elapsed = new ArrayList<Stopwatch>();
    for(ClientHandler handler : clientHandlers) {
      elapsed.addAll(handler.getThreadElapsed());
    }
    return elapsed;
  }
}
