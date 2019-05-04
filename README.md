# kafka-consumer-lag-exporter

Simple Java (1.8+) based prometheus exporter for Kafka (0.10+) to exposes consumer's group offset information. This exporter uses the conventional consumer API to collect metrics from Kafka, so it's compatible with all Kafka security (authentication/authorization) models.

## Usage

### Options

```
 -c (--config) VAL       : Kafka consumer config file
 -d (--debugEnabled)     : Enable debug (default: false)
 -g (--groupRegex) VAL   : Group regex filter (default: .*)
 -p (--port) N           : Prometheus port (default: 8888)
 -s (--schedulePeriod) N : Metrics collection scheduler interval (ms) (default:
                           5000)
```

### Example (SCRAM-SHA-512 / SASL_SSL)

>>>
consumer.properties
```properties
# list of brokers used for bootstrapping knowledge about the rest of the cluster
bootstrap.servers=localhost:9092

sasl.mechanism=SCRAM-SHA-512
security.protocol=SASL_SSL
ssl.endpoint.identification.algorithm=
ssl.truststore.location=demo.truststore.jks
ssl.truststore.password=changeit
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required \
  username="admin" \
  password="admin-secret";
```
>>>

```bash
java -Xmx50m -Xms50m -jar kafka-consumerlag-exporter.jar  -c consumer.properties -p 9999
```


## Prometheus metrics (Kafka consumer groups)

```
# HELP kafka_consumer_group_lag Consumer group lag information per topic
# TYPE kafka_consumer_group_lag gauge
kafka_consumer_group_lag{clientId="", groupId="",host="", partition="", topic=""}
```

## Prometheus metrics (Exporter)

```
# HELP kafka_consumer_group_exporter_collect_metrics_elapsed_time Time elaped (ms) to collect metrics from kafka cluster, usefull to adjust the schedule period
# TYPE kafka_consumer_group_exporter_collect_metrics_elapsed_time gauge
kafka_consumer_group_exporter_collect_metrics_elapsed_time 

# HELP jvm_memory_bytes_used Used bytes of a given JVM memory area.
# TYPE jvm_memory_bytes_used gauge
jvm_memory_bytes_used{area="",} 

# HELP jvm_memory_bytes_committed Committed (bytes) of a given JVM memory area.
# TYPE jvm_memory_bytes_committed gauge
jvm_memory_bytes_committed{area="",}

# HELP jvm_memory_bytes_max Max (bytes) of a given JVM memory area.
# TYPE jvm_memory_bytes_max gauge
jvm_memory_bytes_max{area="",} 

# HELP jvm_memory_bytes_init Initial bytes of a given JVM memory area.
# TYPE jvm_memory_bytes_init gauge
jvm_memory_bytes_init{area="",} 

# HELP jvm_memory_pool_bytes_used Used bytes of a given JVM memory pool.
# TYPE jvm_memory_pool_bytes_used gauge
jvm_memory_pool_bytes_used{pool="",}

# HELP jvm_memory_pool_bytes_committed Committed bytes of a given JVM memory pool.
# TYPE jvm_memory_pool_bytes_committed gauge
jvm_memory_pool_bytes_committed{pool="",}

# HELP jvm_memory_pool_bytes_max Max bytes of a given JVM memory pool.
# TYPE jvm_memory_pool_bytes_max gauge
jvm_memory_pool_bytes_max{pool="",} 

# HELP jvm_memory_pool_bytes_init Initial bytes of a given JVM memory pool.
# TYPE jvm_memory_pool_bytes_init gauge
jvm_memory_pool_bytes_init{pool="",}

# HELP jvm_threads_current Current thread count of a JVM
# TYPE jvm_threads_current gauge
jvm_threads_current

# HELP jvm_threads_daemon Daemon thread count of a JVM
# TYPE jvm_threads_daemon gauge
jvm_threads_daemon

# HELP jvm_threads_peak Peak thread count of a JVM
# TYPE jvm_threads_peak gauge
jvm_threads_peak 

# HELP jvm_threads_started_total Started thread count of a JVM
# TYPE jvm_threads_started_total counter
jvm_threads_started_total 

# HELP jvm_threads_deadlocked Cycles of JVM-threads that are in deadlock waiting to acquire object monitors or ownable synchronizers
# TYPE jvm_threads_deadlocked gauge
jvm_threads_deadlocked

# HELP jvm_threads_deadlocked_monitor Cycles of JVM-threads that are in deadlock waiting to acquire object monitors
# TYPE jvm_threads_deadlocked_monitor gauge
jvm_threads_deadlocked_monitor

# HELP jvm_threads_state Current count of threads by state
# TYPE jvm_threads_state gauge
jvm_threads_state{state="",}

# HELP jvm_info JVM version info
# TYPE jvm_info gauge
jvm_info{version="11.0.3+7-LTS",vendor="Oracle Corporation",runtime="OpenJDK Runtime Environment",}

# HELP process_cpu_seconds_total Total user and system CPU time spent in seconds.
# TYPE process_cpu_seconds_total counter
process_cpu_seconds_total 

# HELP process_start_time_seconds Start time of the process since unix epoch in seconds.
# TYPE process_start_time_seconds gauge
process_start_time_seconds 

# HELP process_open_fds Number of open file descriptors.
# TYPE process_open_fds gauge
process_open_fds

# HELP process_max_fds Maximum number of open file descriptors.
# TYPE process_max_fds gauge
process_max_fds

# HELP process_virtual_memory_bytes Virtual memory size in bytes.
# TYPE process_virtual_memory_bytes gauge
process_virtual_memory_bytes 

# HELP process_resident_memory_bytes Resident memory size in bytes.
# TYPE process_resident_memory_bytes gauge
process_resident_memory_bytes 

# HELP jvm_classes_loaded The number of classes that are currently loaded in the JVM
# TYPE jvm_classes_loaded gauge
jvm_classes_loaded 

# HELP jvm_classes_loaded_total The total number of classes that have been loaded since the JVM has started execution
# TYPE jvm_classes_loaded_total counter
jvm_classes_loaded_total 

# HELP jvm_classes_unloaded_total The total number of classes that have been unloaded since the JVM has started execution
# TYPE jvm_classes_unloaded_total counter
jvm_classes_unloaded_total 

# HELP jvm_buffer_pool_used_bytes Used bytes of a given JVM buffer pool.
# TYPE jvm_buffer_pool_used_bytes gauge
jvm_buffer_pool_used_bytes{pool="",} 

# HELP jvm_buffer_pool_capacity_bytes Bytes capacity of a given JVM buffer pool.
# TYPE jvm_buffer_pool_capacity_bytes gauge
jvm_buffer_pool_capacity_bytes{pool="",}

# HELP jvm_buffer_pool_used_buffers Used buffers of a given JVM buffer pool.
# TYPE jvm_buffer_pool_used_buffers gauge
jvm_buffer_pool_used_buffers{pool="",}
```

## Prometheus queries

```
# LAG per group/topic
sum(kafka_consumer_group_lag) by (groupId, topic)

# Number of consumers per group/topic
count(kafka_consumer_group_lag{clientId != "-"}) by(groupId, topic)

# Number of partitions per topic
count(count(kafka_consumer_group_lag{clientId != "-", topic=~"$topic"}) without (clientId)) by (topic)
```
