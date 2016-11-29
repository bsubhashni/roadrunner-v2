/**
 * Copyright (C) 2009-2013 Couchbase, Inc.
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALING
 * IN THE SOFTWARE.
 */

package com.couchbase.roadrunner;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.CouchbaseCluster;
import com.couchbase.client.java.env.CouchbaseEnvironment;
import com.couchbase.client.java.env.DefaultCouchbaseEnvironment;
import com.couchbase.roadrunner.customConverter.ByteJsonTranscoder;
import com.couchbase.roadrunner.workloads.DocumentGenerator;
import com.couchbase.roadrunner.workloads.Workload;
import com.google.common.base.Stopwatch;

/**
 * The ClientHandler is responsible for managing its own thread pool and
 * dispatching the given workloads to run.
 */
class ClientHandler {


	static {
		System.setProperty("com.couchbase.sentRequestQueueLimit", String.valueOf(Integer.MAX_VALUE));
	}

	private final GlobalConfig config;

	private final ThreadPoolExecutor executor;

	private final Bucket client;

	private final String id;

	private final long numDocs;

	private final int clientOffset;

	private Map<String, List<Stopwatch>> mergedMeasures;

	private DocumentGenerator documentGenerator;

	private List<Workload> workers;

	/**
	 * Initialize the ClientHandler object.
	 *
	 * @param config the global configuration object.
	 */
	public ClientHandler(GlobalConfig config, String id, long numDocs, int offset,
						 DocumentGenerator documentGenerator) throws Exception {
		this.config = config;
		this.id = id;
		this.numDocs = numDocs;
		this.clientOffset = offset;
		this.documentGenerator = documentGenerator;

		CouchbaseEnvironment env = DefaultCouchbaseEnvironment.builder()
				.kvEndpoints(4)
				.callbacksOnIoPool(true)
				.build();
		Cluster cluster = CouchbaseCluster.create(env, config.getNodes());
		this.client = cluster.openBucket(config.getBucket(), config.getPassword(), Collections.singletonList(new ByteJsonTranscoder()));

		this.executor = new ThreadPoolExecutor(
				config.getNumThreads(),
				config.getNumThreads(),
				1,
				TimeUnit.HOURS,
				new ArrayBlockingQueue<Runnable>(config.getNumThreads(), true),
				new ThreadPoolExecutor.CallerRunsPolicy()
		);

		this.workers = new ArrayList<>();
		this.mergedMeasures = new HashMap<>();
	}

	/**
	 * Execute the given workload against the workers.
	 *
	 * @throws Exception
	 */
	public void executeWorkload(DocumentGenerator documentGenerator) throws Exception {
		int workerThreads = config.getNumThreads();
		int docsPerThread = (int) Math.floor(numDocs / workerThreads);
		int workerOffset = this.clientOffset;
		for (int i = 0; i < workerThreads; i++) {
			Workload workloadWorker = new Workload(this.id + "/Workload-" + (i + 1), this.client, config, documentGenerator, docsPerThread, workerOffset);
			workers.add(workloadWorker);
			executor.execute(workloadWorker);
			workerOffset += docsPerThread;
		}
	}

	/**
	 * Cleanup after workload execution and store the measures.
	 *
	 * @throws Exception
	 */
	public void cleanup() throws Exception {
		while (true) {
			if (executor.getActiveCount() == 0) {
				executor.shutdown();
				break;
			}
		}
		executor.awaitTermination(1, TimeUnit.MINUTES);
		storeMeasures();
		//close bucket and wait for it to close
		this.client.close();
	}

	/**
	 * Aggregate and store the calculated measurements.
	 */
	private void storeMeasures() {
		for (Workload workloadWorker : workers) {
			Map<String, List<Stopwatch>> measures = workloadWorker.getMeasures();
			for (Map.Entry<String, List<Stopwatch>> entry : measures.entrySet()) {
				if (mergedMeasures.containsKey(entry.getKey())) {
					mergedMeasures.get(entry.getKey()).addAll(entry.getValue());
				} else {
					mergedMeasures.put(entry.getKey(), entry.getValue());
				}
			}
		}
	}

	/**
	 * Returns the aggregated measures.
	 * @return the measures.
	 */
	public Map<String, List<Stopwatch>> getMeasures() {
		return mergedMeasures;
	}

	public long getTotalOps() {
		long totalOps = 0;
		for (Workload workloadWorker : workers) {
			totalOps += workloadWorker.getTotalOps();
		}
		return totalOps;
	}

	public long getMeasuredOps() {
		long measuredOps = 0;
		for (Workload workloadWorker : workers) {
			measuredOps += workloadWorker.getMeasuredOps();
		}
		return measuredOps;
	}

	public List<Stopwatch> getThreadElapsed() {
		List<Stopwatch> elapsed = new ArrayList<Stopwatch>();
		for (Workload workloadWorker : workers) {
			elapsed.add(workloadWorker.totalElapsed());
		}
		return elapsed;
	}
}
