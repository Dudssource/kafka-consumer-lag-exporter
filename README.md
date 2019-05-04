# kafka-consumer-lag-exporter

Simple Java (1.8+) based prometheus exporter for Kafka (0.10+) to exposes consumer's group offset information. This exporter uses the conventional consumer API to collect metrics from Kafka, so it's compatible with all Kafka security (authentication/authorization) models.

## Usage

```
# Options:

 -c (--config) VAL       : Kafka consumer config file
 -d (--debugEnabled)     : Enable debug (default: false)
 -g (--groupRegex) VAL   : Group regex filter (default: .*)
 -p (--port) N           : Prometheus port (default: 8888)
 -s (--schedulePeriod) N : Metrics collection scheduler interval (ms) (default:
                           5000)
# Example: 

java -Xmx50m -Xms50m -jar kafka-consumerlag-exporter.jar  -c (--config) VAL -d (--debugEnabled) -g (--groupRegex) VAL -p (--port) N -s (--schedulePeriod) N
```

## Prometheus metrics

```
# HELP kafka_consumer_group_lag Consumer group lag information per topic
# TYPE kafka_consumer_group_lag gauge
kafka_consumer_group_lag{clientId="", groupId="",host="", partition="", topic=""}

# LAG per group/topic
sum(kafka_consumer_group_lag) by (groupId, topic)

# Number of consumers per group/topic
count(kafka_consumer_group_lag{clientId != "-"}) by(groupId, topic)

# Number of partitions per topic
count(count(kafka_consumer_group_lag{clientId != "-", topic=~"$topic"}) without (clientId)) by (topic)
```
