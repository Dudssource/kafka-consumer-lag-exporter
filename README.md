# kafka-consumer-lag-exporter
Simple Java (1.8+) based prometheus exporter for Kafka (0.10+) to exposes consumer's group offset information. This exporter uses the conventional consumer API to collect metrics from Kafka, so it's compatible with all Kafka security (authentication/authorization) models.
