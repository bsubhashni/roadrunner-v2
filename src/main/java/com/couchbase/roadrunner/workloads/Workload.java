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

package com.couchbase.roadrunner.workloads;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.couchbase.client.core.BackpressureException;
import com.couchbase.client.java.Bucket;
import com.couchbase.roadrunner.GlobalConfig;
import com.couchbase.roadrunner.customConverter.ByteJsonDocument;
import com.google.common.base.Stopwatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.functions.Func1;

public class Workload implements Runnable {
	private final Logger logger =
			LoggerFactory.getLogger(Workload.class.getName());

	private final Bucket bucket;

	private final String workloadName;

	private final GlobalConfig config;

	private long measuredOps;

	private long totalOps;

	private Stopwatch elapsed;

	private Map<String, List<Stopwatch>> measures;

	protected DocumentGenerator documentGenerator;

	private int count;

	private int start;

	public Workload(String workloadName, Bucket bucket, GlobalConfig config, DocumentGenerator documentGenerator,
					int count, int offset) {
		this.count = count;
		this.start = offset;
		this.workloadName = workloadName;
		this.bucket = bucket;
		this.config = config;
		this.measures = new HashMap<>();
		this.measuredOps = 0;
		this.totalOps = 0;
		this.elapsed = new Stopwatch();
		this.documentGenerator = documentGenerator;
	}

	@Override
	public void run() {
		startTimer();
		Thread.currentThread().setName(getWorkloadName());
		int numBatches = this.count / config.getBatchSize();
		int samplingInterval = 0;
		if (config.getSamplingCount() > 0) {
			samplingInterval = config.getSamplingCount() / config.getNumThreads();
		}

		int index = this.start;
		for (int i = 0; i < numBatches; i++) {
			CountDownLatch latch = new CountDownLatch(config.getBatchSize());

			if (config.getPhase().compareTo("run") == 0) {
				int readCount = (config.getReadratio() * config.getBatchSize()) / 100;
				int writeCount = (config.getWriteratio() * config.getBatchSize()) / 100;

				while (writeCount-- > 0) {
					boolean measure = index != 0 && samplingInterval != 0 && index % samplingInterval == 0;
					update(config.getKeyPrefix() + start + index, measure)
							.subscribe(
									doc -> {},
									err -> {incrTotalOps();err.printStackTrace();latch.countDown();},
									() -> {incrTotalOps();latch.countDown();}
							);
					index++;
				}

				while (readCount-- > 0) {
					boolean measure = index != 0 && samplingInterval != 0 && index % samplingInterval == 0;
					get(config.getKeyPrefix() + start + index, measure)
							.subscribe(
									doc -> {},
									err -> {incrTotalOps();err.printStackTrace();latch.countDown();},
									() -> {incrTotalOps();latch.countDown();}
							);
					index++;
				}
			} else if (config.getPhase().compareTo("load") == 0){
				int insertCount = config.getBatchSize();
				while (insertCount-- > 0) {
					insertWorkload(config.getKeyPrefix() + start + index++)
							.subscribe(
									doc -> {},
									err -> {incrTotalOps();err.printStackTrace();latch.countDown();},
									() -> {incrTotalOps();latch.countDown();}
							);
				}
			}
			try {
				latch.await();
			} catch (InterruptedException ex) {
				ex.printStackTrace();
			}
		}

		try {
			Thread.sleep((long) Math.random() * config.getMaxThinkTime() + config.getMinThinkTime());
		} catch (InterruptedException ex) {
			ex.printStackTrace();
		}
		System.out.println("Completed" + this.workloadName);
		endTimer();
	}


	private Observable<ByteJsonDocument> update(String key, boolean measure) {
		if (measure) {
			return Observable.defer(() -> {
				Stopwatch watch = new Stopwatch().start();
				return _update(key)
						.timeout(1, TimeUnit.SECONDS)
						.doOnTerminate(() -> {
							watch.stop();
							addMeasure("set", watch);
						});
			});
		} else {
			return _update(key);
		}
	}

	private Observable<ByteJsonDocument> _update(String key) {
		final ByteJsonDocument document = documentGenerator.getDocument(key);
		return getBucket().async().upsert(document).retryWhen(errors -> errors.flatMap((Func1<Throwable, Observable<?>>) throwable -> {
							if (throwable instanceof BackpressureException) {
									return Observable.timer(1, TimeUnit.MICROSECONDS);
								}
							return Observable.error(throwable);
						}));
	}

	private Observable<ByteJsonDocument> get(String key, boolean measure) {
		if (measure) {
			return Observable.defer(() -> {
				Stopwatch watch = new Stopwatch().start();
				return _get(key)
						.timeout(1, TimeUnit.SECONDS)
						.doOnTerminate(() -> {
							watch.stop();
							addMeasure("get", watch);
						});
			});
		} else {
			return _get(key);
		}
	}

	private Observable<ByteJsonDocument> _get(String key) {
		return getBucket().async().get(key, ByteJsonDocument.class).retryWhen(errors -> errors.flatMap((Func1<Throwable, Observable<?>>) throwable -> {
			if (throwable instanceof BackpressureException) {
				return Observable.timer(1, TimeUnit.MICROSECONDS);
			}
			return Observable.error(throwable);
		}));
	}

	private Observable<ByteJsonDocument> insertWorkload(String key) {
		final ByteJsonDocument document = documentGenerator.getDocument(key);
		return getBucket().async().insert(document).timeout(1, TimeUnit.SECONDS);
	}


	public long getTotalOps() {
		return totalOps;
	}

	public synchronized void incrTotalOps() {
		totalOps++;
	}

	public void startTimer() {
		elapsed.start();
	}

	public void endTimer() {
		elapsed.stop();
	}

	/**
	 * Store a measure for later retrieval.
	 *
	 * @param identifier Identifier of the stopwatch.
	 * @param watch The stopwatch.
	 */
	public void addMeasure(String identifier, Stopwatch watch) {
		if (!measures.containsKey(identifier)) {
			measures.put(identifier, new ArrayList<Stopwatch>());
		}
		measures.get(identifier).add(watch);
		measuredOps++;
	}

	public Map<String, List<Stopwatch>> getMeasures() {
		return measures;
	}

	public long getMeasuredOps() {
		return measuredOps;
	}

	public Stopwatch totalElapsed() {
		if (elapsed.isRunning()) {
			throw new IllegalStateException("Stopwatch still running!");
		}
		return elapsed;
	}

	/**
	 * @return the bucket
	 */
	protected Bucket getBucket() {
		return bucket;
	}

	/**
	 * @return the workloadName
	 */
	public String getWorkloadName() {
		return workloadName;
	}

}
