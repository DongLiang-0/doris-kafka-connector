/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.doris.kafka.connector.converter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.sink.SinkRecord;

public class RecordDescriptor {
    private final SinkRecord record;
    private final String topicName;
    private final List<String> keyFieldNames;
    private final List<String> nonKeyFieldNames;
    private final Map<String, FieldDescriptor> fields;
    private final boolean flattened;

    private RecordDescriptor(
            SinkRecord record,
            String topicName,
            List<String> keyFieldNames,
            List<String> nonKeyFieldNames,
            Map<String, FieldDescriptor> fields,
            boolean flattened) {
        this.record = record;
        this.topicName = topicName;
        this.keyFieldNames = keyFieldNames;
        this.nonKeyFieldNames = nonKeyFieldNames;
        this.fields = fields;
        this.flattened = flattened;
    }

    public String getTopicName() {
        return topicName;
    }

    public Integer getPartition() {
        return record.kafkaPartition();
    }

    public long getOffset() {
        return record.kafkaOffset();
    }

    public List<String> getKeyFieldNames() {
        return keyFieldNames;
    }

    public List<String> getNonKeyFieldNames() {
        return nonKeyFieldNames;
    }

    public Map<String, FieldDescriptor> getFields() {
        return fields;
    }

    public boolean isDebeziumSinkRecord() {
        return !flattened;
    }

    public boolean isTombstone() {
        // Debezium TOMBSTONE has both value and valueSchema to null.
        return record.value() == null && record.valueSchema() == null;
    }

    public boolean isDelete() {
        if (!isDebeziumSinkRecord()) {
            return record.value() == null;
        } else if (record.value() != null) {
            final Struct value = (Struct) record.value();
            return "d".equals(value.getString("op"));
        }
        return false;
    }

    public Struct getAfterStruct() {
        if (isDebeziumSinkRecord()) {
            return ((Struct) record.value()).getStruct("after");
        } else {
            return ((Struct) record.value());
        }
    }

    public Struct getBeforeStruct() {
        if (isDebeziumSinkRecord()) {
            return ((Struct) record.value()).getStruct("before");
        } else {
            return ((Struct) record.value());
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class FieldDescriptor {
        private final Schema schema;
        private final String name;
        private final String schemaTypeName;
        private final String schemaName;

        public FieldDescriptor(
                Schema schema, String name, String schemaTypeName, String schemaName) {
            this.schema = schema;
            this.name = name;
            this.schemaTypeName = schemaTypeName;
            this.schemaName = schemaName;
        }

        public String getName() {
            return name;
        }

        public String getSchemaName() {
            return schemaName;
        }

        public Schema getSchema() {
            return schema;
        }

        public String getSchemaTypeName() {
            return schemaTypeName;
        }
    }

    public static class Builder {

        private SinkRecord sinkRecord;

        // Internal build state
        private final List<String> keyFieldNames = new ArrayList<>();
        private final List<String> nonKeyFieldNames = new ArrayList<>();
        private final Map<String, FieldDescriptor> allFields = new LinkedHashMap<>();

        public Builder withSinkRecord(SinkRecord record) {
            this.sinkRecord = record;
            return this;
        }

        public RecordDescriptor build() {
            Objects.requireNonNull(sinkRecord, "The sink record must be provided.");

            final boolean flattened = !isTombstone(sinkRecord) && isFlattened(sinkRecord);
            readSinkRecordNonKeyData(sinkRecord, flattened);

            return new RecordDescriptor(
                    sinkRecord,
                    sinkRecord.topic(),
                    keyFieldNames,
                    nonKeyFieldNames,
                    allFields,
                    flattened);
        }

        private boolean isFlattened(SinkRecord record) {
            return record.valueSchema().name() == null
                    || !record.valueSchema().name().contains("Envelope");
        }

        private boolean isTombstone(SinkRecord record) {

            return record.value() == null && record.valueSchema() == null;
        }

        private void readSinkRecordNonKeyData(SinkRecord record, boolean flattened) {
            final Schema valueSchema = record.valueSchema();
            if (valueSchema != null) {
                if (flattened) {
                    // In a flattened event type, it's safe to read the field names directly
                    // from the schema as this isn't a complex Debezium message type.
                    applyNonKeyFields(valueSchema);
                } else {
                    final Field after = valueSchema.field("after");
                    if (after == null) {
                        throw new ConnectException(
                                "Received an unexpected message type that does not have an 'after' Debezium block");
                    }
                    applyNonKeyFields(after.schema());
                }
            }
        }

        private void applyNonKeyFields(Schema schema) {
            for (Field field : schema.fields()) {
                if (!keyFieldNames.contains(field.name())) {
                    applyNonKeyField(field.name(), field.schema());
                }
            }
        }

        private void applyNonKeyField(String name, Schema schema) {
            FieldDescriptor fieldDescriptor =
                    new FieldDescriptor(schema, name, schema.type().name(), schema.name());
            nonKeyFieldNames.add(fieldDescriptor.getName());
            allFields.put(fieldDescriptor.getName(), fieldDescriptor);
        }
    }
}
