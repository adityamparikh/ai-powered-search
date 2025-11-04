#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -e

# Enable verbose error reporting
exec 2>&1

echo "Starting Solr in SolrCloud mode..."
# Start Solr in background
/opt/solr/bin/solr start -c -z zoo:2181

# Wait for Solr to be up
until curl -s http://localhost:8983/solr/ > /dev/null; do
  echo "Waiting for Solr to start..."
  sleep 2
done

# Wait for ZooKeeper to be fully ready for SolrCloud operations
echo "Waiting for ZooKeeper to be ready for SolrCloud operations..."
sleep 10

# Wait for cluster state to be ready
until curl -s "http://localhost:8983/solr/admin/collections?action=CLUSTERSTATUS" | grep -q '"responseHeader"'; do
  echo "Waiting for SolrCloud cluster to be ready..."
  sleep 2
done

# Delete existing configset if it exists (for clean updates)
echo "Checking for existing configset..."
if /opt/solr/bin/solr zk ls /configs/ai-powered-search-config -z zoo:2181 2>/dev/null; then
  echo "Deleting existing configset..."
  /opt/solr/bin/solr zk rm -r /configs/ai-powered-search-config -z zoo:2181 || true
  sleep 2
fi

# Upload custom configset to ZooKeeper
echo "Uploading custom configset to ZooKeeper..."
/opt/solr/bin/solr zk upconfig -n ai-powered-search-config -d /opt/solr-config/conf -z zoo:2181

# Function to create collection with custom schema
create_collection_with_schema() {
  local COLLECTION_NAME=$1

  # Check if collection already exists
  if curl -s "http://localhost:8983/solr/admin/collections?action=LIST" | grep -q "\"$COLLECTION_NAME\""; then
    echo "Collection '$COLLECTION_NAME' already exists, skipping creation."
    return 0
  fi

  echo "Creating collection '$COLLECTION_NAME' with custom schema..."
  set +e  # Temporarily disable exit on error
  /opt/solr/bin/solr create -c "$COLLECTION_NAME" -n ai-powered-search-config
  if [ $? -ne 0 ]; then
    echo "First attempt failed, retrying collection creation..."
    sleep 5
    /opt/solr/bin/solr create -c "$COLLECTION_NAME" -n ai-powered-search-config
    if [ $? -ne 0 ]; then
      echo "ERROR: Collection creation failed for '$COLLECTION_NAME'"
      echo "Checking Solr logs for details..."
      tail -100 /var/solr/logs/solr.log
      set -e  # Re-enable exit on error
      return 1
    fi
  fi
  set -e  # Re-enable exit on error

  # Wait for collection to be ready
  until curl -s "http://localhost:8983/solr/admin/collections?action=LIST" | grep -q "\"$COLLECTION_NAME\""; do
    echo "Waiting for $COLLECTION_NAME collection to be ready..."
    sleep 2
  done

  echo "Collection '$COLLECTION_NAME' created successfully with custom schema!"
}

# Create collections with custom schema
create_collection_with_schema "books"

echo ""
echo "=========================================="
echo "Solr initialization complete!"
echo "=========================================="
echo "Collections created:"
echo "  - books (with custom schema)"
echo ""
echo "Custom configset: ai-powered-search-config"
echo "Schema location: /opt/solr-config/conf/managed-schema.xml"
echo ""
echo "Vector field configuration:"
echo "  - Field name: vector"
echo "  - Dimensions: 1536"
echo "  - Similarity: cosine"
echo "  - Algorithm: HNSW"
echo "=========================================="

# Stop background Solr and run in foreground
/opt/solr/bin/solr stop
exec /opt/solr/bin/solr start -c -z zoo:2181 -f
