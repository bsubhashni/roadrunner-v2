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

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.HdrHistogram.Histogram;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;

/**
 * The RoadRunner project is a load tester for your Couchbase cluster.
 *
 * With this load tester, you can specify the number for CouchbaseClients and
 * corresponding worker threads to execute a workload against the connected
 * cluster. In addition to producing raw workload, it is able to measure
 * latency and throughput, therefore giving you a tool to measure the
 * performance of your cluster and JVM environment by testing it in isolation.
 * You can use it for both performance and debugging purposes.
 *
 * By default, it connects to localhost, using the "default" bucket with no
 * password. Also, it uses one CouchbaseClient and one worker thread. You can
 * change the behavior by providing custom command line arguments. Use the
 * "-h" flag to see all available options.
 */
public final class RoadRunner {

	private static final Logger LOGGER =
			LoggerFactory.getLogger(RoadRunner.class.getName());

	public static final String OPT_NODES = "nodes";
	public static final String OPT_BUCKET = "bucket";
	public static final String OPT_PASSWORD = "password";
	public static final String OPT_NUM_THREADS = "num-threads";
	public static final String OPT_NUM_CLIENTS = "num-clients";
	public static final String OPT_NUM_DOCS = "num-docs";
	public static final String OPT_READRATIO = "read-ratio";
	public static final String OPT_WRITERATIO = "write-ratio";
	public static final String OPT_PHASE = "phase";
	public static final String OPT_RAMP = "ramp";
	public static final String OPT_BATCHSIZE = "batch-size";
	public static final String OPT_HELP = "help";
	public static final String OPT_SAMPLING = "sampling";
	public static final String OPT_CLASS_NAME = "class";
	public static final String OPT_MINTHINKTIME = "min-thinktime";
	public static final String OPT_MAXTHINKTIME = "max-thinktime";

	public static final String DEFAULT_NODES = "127.0.0.1";
	public static final String DEFAULT_BUCKET = "default";
	public static final String DEFAULT_PASSWORD = "";
	public static final String DEFAULT_NUM_THREADS = "1";
	public static final String DEFAULT_NUM_CLIENTS = "1";
	public static final String DEFAULT_NUM_DOCS = "1000";
	public static final String DEFAULT_READ_RATIO = "50";
	public static final String DEFAULT_WRITE_RATIO = "50";
	public static final String DEFAULT_SAMPLING = "100";
	public static final String DEFAULT_PHASE = "run";
	public static final String DEFAULT_RAMP = "0";
	public static final String DEFAULT_CLASS = "Simple";
	public static final String DEFAULT_MIN_THINKTIME = "1";
	public static final String DEFAULT_MAX_THINKTIME = "1000";
	public static final String DEFAULT_BATCHSIZE = "2";

	private RoadRunner() {
	}

	/**
	 * Initialize the RoadRunner.
	 *
	 * This method is responsible for parsing the passed in command line arguments
	 * and also dispatch the bootstrapping of the actual workload runner.
	 *
	 * @param args Command line arguments to be passed in.
	 */
	public static void main(final String[] args) {
		CommandLine params = null;
		try {
			params = parseCommandLine(args);
		} catch (ParseException ex) {
			LOGGER.error("Exception while parsing command line!", ex);
			System.exit(-1);
		}

		if (params.hasOption(OPT_HELP)) {
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp("roadrunner", getCommandLineOptions());
			System.exit(0);
		}

		GlobalConfig config = new GlobalConfig(params);
		WorkloadDispatcher dispatcher = new WorkloadDispatcher(config);

		LOGGER.info("Running with Config: " + config.toString());

		try {
			LOGGER.debug("Initializing ClientHandlers");
			dispatcher.init();
		} catch (Exception ex) {
			LOGGER.error("Error while initializing the ClientHandlers: ", ex);
			System.exit(-1);
		}

		Stopwatch workloadStopwatch = new Stopwatch().start();
		try {
			LOGGER.info("Running Workload");
			dispatcher.dispatchWorkload();
		} catch (Exception ex) {
			LOGGER.error("Error while running the Workload: ", ex);
			System.exit(-1);
		}
		workloadStopwatch.stop();

		LOGGER.debug("Finished Workload");

		LOGGER.info("==== RESULTS ====");

		dispatcher.prepareMeasures();

		long totalOps = dispatcher.getTotalOps();
		long measuredOps = dispatcher.getMeasuredOps();

		LOGGER.info("Operations: measured " + measuredOps + " ops out of total "
				+ totalOps + "ops");

		Map<String, List<Stopwatch>> measures = dispatcher.getMeasures();
		for (Map.Entry<String, List<Stopwatch>> entry : measures.entrySet()) {
			Histogram h = new Histogram(10 * 60 * 1000 * 1000, 5);
			for (Stopwatch watch : entry.getValue()) {
				h.recordValue(watch.elapsed(TimeUnit.MICROSECONDS));
			}

			LOGGER.info("Percentile (microseconds) for \"" + entry.getKey() + "\" Workload:");
			LOGGER.info("   50%:" + (Math.round(h.getValueAtPercentile(0.5) * 100) / 100)
					+ "   75%:" + (Math.round(h.getValueAtPercentile(0.75) * 100) / 100)
					+ "   95%:" + (Math.round(h.getValueAtPercentile(0.95) * 100) / 100)
					+ "   99%:" + (Math.round(h.getValueAtPercentile(0.99) * 100) / 100));
		}

		LOGGER.info("Elapsed: " + workloadStopwatch.elapsed(TimeUnit.MILLISECONDS) + "ms");

		List<Stopwatch> elapsedThreads = dispatcher.getThreadElapsed();
		long shortestThread = 0;
		long longestThread = 0;
		for (Stopwatch threadWatch : elapsedThreads) {
			long threadMs = threadWatch.elapsed(TimeUnit.MILLISECONDS);
			if (longestThread == 0 || threadMs > longestThread) {
				longestThread = threadMs;
			}
			if (shortestThread == 0 || threadMs < shortestThread) {
				shortestThread = threadMs;
			}
		}

		LOGGER.info("Shortest Thread: " + shortestThread + "ms");
		LOGGER.info("Longest Thread: " + longestThread + "ms");
	}

	/**
	 * Parse the command line.
	 *
	 * @param args Command line arguments to be passed in.
	 * @return The parsed command line options.
	 * @throws ParseException Thrown when the command line could not be parsed.
	 */
	@VisibleForTesting
	static CommandLine parseCommandLine(final String[] args)
			throws ParseException {
		CommandLineParser parser = new PosixParser();
		CommandLine params = parser.parse(getCommandLineOptions(), args);
		return params;
	}

	/**
	 * Defines the default command line options.
	 *
	 * @return Supported command line options.
	 */
	@VisibleForTesting
	static Options getCommandLineOptions() {
		Options options = new Options();

		options.addOption("P", OPT_PHASE, true,
				"load/run phase (default: \""
						+ DEFAULT_PHASE + "\")");

		options.addOption("n", OPT_NODES, true,
				"List of nodes to connect, separated with \",\" (default: \"" + DEFAULT_NODES + "\")");

		options.addOption("b", OPT_BUCKET, true,
				"Name of the bucket (default: \"" + DEFAULT_BUCKET + "\")");

		options.addOption("p", OPT_PASSWORD, true,
				"Password of the bucket (default: \"" + DEFAULT_PASSWORD + "\")");

		options.addOption("t", OPT_NUM_THREADS, true,
				"Number of worker threads per CouchbaseClient object (default: \"" + DEFAULT_NUM_THREADS + "\")");

		options.addOption("c", OPT_NUM_CLIENTS, true,
				"Number of CouchbaseClient objects (default: \"" + DEFAULT_NUM_CLIENTS + "\")");

		options.addOption("d", OPT_NUM_DOCS, true,
				"Number of documents to work with (default: \"" + DEFAULT_NUM_DOCS + "\")");

		options.addOption("B", OPT_BATCHSIZE, true,
				"Batch size (default: \"" + DEFAULT_BATCHSIZE + "\")");

		options.addOption("g", OPT_READRATIO , true,
				"Read Ratio  (default: \"" + DEFAULT_READ_RATIO + "\")");

		options.addOption("w", OPT_WRITERATIO , true,
				"Write Ratio (default: \"" + DEFAULT_READ_RATIO + "\")");

		options.addOption("s", OPT_SAMPLING, true,
				"% Sample Rate (default: \"" + DEFAULT_SAMPLING + "%\")");

		options.addOption("R", OPT_RAMP, true,
				"Ramp-Up time in seconds - ignored ops (default: \"" + DEFAULT_RAMP + "\")");

		options.addOption("C", OPT_CLASS_NAME, true,
				"Class name from the sample classes (default: \"" + DEFAULT_CLASS + "\")");

		options.addOption("z", OPT_MINTHINKTIME, true,
				"Minimum think time (default: \"" + DEFAULT_MIN_THINKTIME + "\")");

		options.addOption("Z", OPT_MAXTHINKTIME, true,
				"Maximum think time (default: \"" + DEFAULT_MAX_THINKTIME + "\")");

		options.addOption("h", OPT_HELP, false,
				"Print this help message");

		return options;
	}
}
