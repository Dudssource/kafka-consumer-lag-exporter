package org.dudssource.consumerlagexporter;

import java.io.FileInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionHandlerFilter;

import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;

/**
 * Kafka consumer group lag prometheus exporter tool.
 *
 * @author eduardo.goncalves
 *
 */
public class ConsumerLagExporter extends TimerTask {

	private final static Logger LOG = Logger.getLogger(ConsumerLagExporter.class);

	@Option(name = "-c", aliases = { "--config" }, usage = "Kafka consumer config file", required = true)
	private String config;

	@Option(name = "-g", aliases = { "--groupRegex" }, usage = "Group regex filter", required = false)
	private String groupRegex = ".*";

	@Option(name = "-p", aliases = { "--port" }, usage = "Prometheus port", required = false)
	private Integer port = 8888;

	@Option(name = "-s", aliases = { "--schedulePeriod" }, usage = "Metrics collection scheduler interval (ms)", required = false)
	private Long schedulePeriod = 5000L;

	@Option(name = "-d", aliases = { "--debugEnabled" }, usage = "Enable debug", required = false)
	private boolean debugEnabled = false;

	/**
	 * Consumer Group capture pattern
	 */
	private Pattern pattern;

	/**
	 * Prometheus HTTP server.
	 */
	private HTTPServer prometheus;

	/**
	 * Java timer (scheduler)
	 */
	private Timer metricsScheduler;

	/**
	 * Prometheus gauge.
	 */
	private static final Gauge KAFKA_CONSUMER_GROUP_LAG = Gauge.build().name("kafka_consumer_group_lag")
			.labelNames("groupId", "topic", "partition", "host", "clientId")
			.help("Consumer group lag information per topic").register();

	private static final Gauge COLLECT_METRICS_ELAPSED_TIME = Gauge.build()
			.name("kafka_consumer_group_exporter_collect_metrics_elapsed_time")
			.help("Time elaped (ms) to collect metrics from kafka cluster, usefull to adjust the schedule period")
			.register();

	/**
	 * Kafka Admin client
	 */
	private AdminClient adminClient;

	/**
	 * Executor to parallelize processing
	 */
	private ExecutorService executor;

	/**
	 * The time to wait for the executor to shutdown.
	 */
	private final static int DEFAULT_EXECUTOR_SHUTDOWN_TIMEOUT = 120;

	/**
	 * Startup.
	 *
	 * @param args
	 * @throws Exception 
	 * @throws InterruptedException 
	 */
	public static void main(String[] args) throws InterruptedException, Exception {

		final ConsumerLagExporter exporter = new ConsumerLagExporter(args);

		Runtime.getRuntime().addShutdownHook(new Thread() {

			public void run() {
				exporter.stop();
			}
		});

		exporter.start();
	}

	/**
	 * Starts the scheduler to collect metrics from kafka.
	 *
	 * @throws Exception
	 */
	private void start() throws Exception {

		if (this.debugEnabled) {
			LOG.setLevel(Level.DEBUG);
			Logger.getLogger("org.apache.kafka").setLevel(Level.DEBUG);
		}

		// executor service
		executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

		// JVM metrics
		DefaultExports.initialize();

		// creates the kafka admin client
		createAdminClient();

		// creating pattern capture
		pattern = Pattern.compile(groupRegex);

		// java timer
		metricsScheduler = new Timer();

		// prometheus HTTP server
		prometheus = new HTTPServer(port);

		// scheduling
		metricsScheduler.schedule(this, 0, schedulePeriod);
	}

	@Override
	public void run() {

		Instant metricsCollectStartTime = Instant.now();

		try {

			KafkaFuture<Collection<ConsumerGroupListing>> consumerGroupsResult =  adminClient.listConsumerGroups().all();

			Set<String> consumerGroups = new HashSet<>();

			for (ConsumerGroupListing consumerGroupResult : consumerGroupsResult.get()) {

				final String groupId = consumerGroupResult.groupId();

				if (!pattern.matcher(groupId).matches()) {
					LOG.debug(String.format("Ignoring group %s due to groupRegex %s", groupId, groupRegex));
					continue;
				}

				consumerGroups.add(groupId);
			}

			// describing the matched groups
			for (Map.Entry<String, ConsumerGroupDescription> consumerGroupDescriptionEntry : adminClient
					.describeConsumerGroups(consumerGroups).all().get().entrySet()) {

				executor.submit(() -> {

					final String groupId = consumerGroupDescriptionEntry.getKey();

					final ConsumerGroupDescription consumerGroupDescription = consumerGroupDescriptionEntry.getValue();

					// getting the consumer for the group
					try (final KafkaConsumer<String, String> consumer = getConsumer(groupId)) {

						// offsets per partition
						final Map<TopicPartition, OffsetAndMetadata> offsetsPerConsumerGroupMap = adminClient
								.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get();

						// using the Kafka consumer API to get the offset end for the group
						Map<TopicPartition, Long> topicPartition = consumer.endOffsets(offsetsPerConsumerGroupMap.keySet());

						for (Map.Entry<TopicPartition, OffsetAndMetadata> consumerGroupOffsetsEntry : offsetsPerConsumerGroupMap
								.entrySet()) {

							final TopicPartition partition = consumerGroupOffsetsEntry.getKey();
							final String topic = partition.topic();

							// partition LAG
							final double partitionLag = (topicPartition.get(partition).longValue()
									- consumerGroupOffsetsEntry.getValue().offset());

							// consumer group member info
							final Optional<MemberDescription> member = getMember(consumerGroupDescription,
									partition);

							if (member.isPresent()) {
								KAFKA_CONSUMER_GROUP_LAG
										.labels(groupId, topic, String.valueOf(partition.partition()),
												member.get().host(), member.get().clientId())
										.set(partitionLag);
							}

							if (!member.isPresent()) {
								KAFKA_CONSUMER_GROUP_LAG.labels(groupId, topic,
										String.valueOf(partition.partition()), "-", "-").set(partitionLag);
							}
						}

					} catch (Exception e) {
						LOG.error("Error trying to extract consumer group offset", e);
					}
				});
			}

		} catch (Exception e) {
			LOG.error("Error trying to extract consumer group metrics", e);
		}

		// calculating the time spent to collect metrics from the kafka cluster
		long metricsCollectTimeElapsed = Duration.between(metricsCollectStartTime, Instant.now()).toMillis();

		// storing the value
		COLLECT_METRICS_ELAPSED_TIME.set(metricsCollectTimeElapsed);

	    if (metricsCollectTimeElapsed > this.schedulePeriod) {

	    	// purging
	    	int purgedTasks = metricsScheduler.purge();

			LOG.warn(String.format(
					"The collector is taking too long to scrap metrics from the Kafka Cluster (%d ms > %d ms), purged %d cancelled tasks from the timer queue",
					metricsCollectTimeElapsed, this.schedulePeriod, purgedTasks));
	    }

	}

	/**
	 * Creates the kafka admin client.
	 */
	private void createAdminClient() {
		Properties props = loadProps(config);
		adminClient = AdminClient.create(props);
	}

	/**
	 * Extracts the information about the consumer group member's host consuming from the partition.
	 *
	 * @param description
	 * @param partition
	 * @return
	 */
	private static Optional<MemberDescription> getMember(ConsumerGroupDescription description, TopicPartition partition) {
		return description.members().stream().filter(m -> m.assignment().topicPartitions().contains(partition)).findFirst();
	}

	/**
	 * Stop application all consumers.
	 */
	private void stop() {

		try {
			if (adminClient != null) {
				LOG.debug("Closing kafka admin client");
				adminClient.close();
			}

		} catch(Exception e) {
			LOG.error("Error closing Kafka Admin Client", e);
		}

		try {

			if (prometheus != null) {

				LOG.debug("Shutting down prometheus HTTP server");
				
				// shutting down prometheus http server
				prometheus.stop();
			}

		} catch (Exception e) {
			LOG.error("Error stopping prometheus HTTP server", e);
		}

		if (executor != null) {

			LOG.info("Closing the executor");

			try {

				// shutting down
				executor.shutdown();

				if (!executor.awaitTermination(DEFAULT_EXECUTOR_SHUTDOWN_TIMEOUT, TimeUnit.SECONDS)) {

					LOG.error("Couldn't shutdown the disruptor in " + DEFAULT_EXECUTOR_SHUTDOWN_TIMEOUT
							+ " seconds executor, forcing");

					// causing the interrupt
					executor.shutdownNow();
				}

			} catch (InterruptedException e) {
				LOG.error("Error trying to close the executor", e);
			}
		}

		try {

			if (metricsScheduler != null) {
				
				LOG.debug("Shutting down metrics scheduler");
				
				// terminating the timer
				metricsScheduler.cancel();
			}

		} catch (Exception e) {
			LOG.error("Error terminating the metrics scheduler", e);
		}
	}

	/**
	 * Get or create the kafka consumer to extract the topic's offsets.
	 *
	 * @param groupId
	 * @return
	 */
	private KafkaConsumer<String, String> getConsumer(String groupId) {

		LOG.debug(String.format("Creating consumer for group %s", groupId));
		
		// creating the new consumer
		Properties consumerProps = loadProps(config);
		consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		consumerProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000");
		
		return new KafkaConsumer<>(consumerProps);
	}

	/**
	 * Load the configuration file.
	 *
	 * @param path
	 * @return
	 */
	private static Properties loadProps(String path) {

		LOG.debug(String.format("Loading kafka consumer properties from %s", path));

		Properties props = new Properties();

		try (FileInputStream is = new FileInputStream(path)) {

			props.load(is);

		} catch (Exception e) {

			LOG.error("Error trying to load the properties file", e);

			throw new RuntimeException(e);
		}

		return props;
	}

	/**
	 * Default constructor.
	 *
	 * @param args
	 */
	public ConsumerLagExporter(String[] args) {

		final CmdLineParser parser = new CmdLineParser(this);

		try {

			// parse the arguments.
			parser.parseArgument(args);

		} catch (CmdLineException e) {

			LOG.error("Error trying to extract program options: " + e.getMessage());
			LOG.error("java program.jar [options...]");
			parser.printUsage(System.err);
			LOG.error("\n  Example: java program.jar " + parser.printExample(OptionHandlerFilter.ALL));

			// saindo do programa com erro
			System.exit(1);
		}
	}
}