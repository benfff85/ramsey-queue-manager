# Ramsey Queue Manager

This service manages the work queue for the Ramsey distributed computing system. It pushes work items to a Redis queue for workers to consume and handles automatic stage progression when improvements are found.

## Architecture

```
┌─────────────────┐     ┌─────────────────────┐     ┌─────────────┐
│  Queue Manager  │────▶│       Redis         │◀────│   Workers   │
│  (QueueFeeder)  │     │ work_queue:{id} (L) │     │ (pop/claim) │
│                 │     │ work_index:{id} (C) │     │             │
│  (Progression)  │◀────│ best_result:{id}    │     │             │
└─────────────────┘     └─────────────────────┘     └─────────────┘
        │
        ▼
  Creates new stage
```
*(L) = Legacy Queue Mode, (C) = Counter Mode*

## Components

### Queue Feeder

Handles initialization and monitoring of work for the active stage. Supports two modes:

**1. Counter-Based Mode (Default/New)**
- Driven by `workEnumerationStrategy` on the Stage.
- Initializes atomic counter `stage_work_index:{id}` at 0.
- Sets `stage_config:{id}` containing graph data and strategy parameters.
- Monitors progress by checking if counter >= `totalPairs`.

**2. Queue-Based Mode (Legacy)**
- Used if no strategy is defined.
- Checks Redis queue depth using `LLEN`.
- If depth < min threshold, generates exact work items and pushes to `work_queue:{id}`.
- Workers consume using `RPOP`.

### Stage Progression Monitor

Runs at an interval defined by `ramsey.stage-progression.frequency-in-millis` (default: 30 seconds).

- Polls Redis for `best_result:{stageId}` key (set by workers when they find improvements)
- Compares best result's clique count to current base graph
- If better, triggers stage progression:
  1. Gets derived graph from middleware (`GET /graphs/{id}?edgesToFlip=...`)
  2. Saves new graph with improved clique count
  3. Marks current stage INACTIVE
  4. Creates new stage with the improved graph
  5. Clears Redis queue and best_result key

### Client Monitor

Monitors client health and marks inactive clients.

- Checks all active clients' last phone home time
- Marks clients as INACTIVE if they haven't phoned home within threshold

## Redis Key Format

**Work Queue:** `work_queue:{stageId}` (List)
```json
{"baseGraphId": 1, "stageId": 6, "edgesToFlip": [...], "analysisType": "TARGETED"}
```

**Best Result:** `best_result:{stageId}` (String - set by workers)
```json
{"baseGraphId": 6, "stageId": 6, "edgesToFlip": [...], "cliqueCount": 1051000}
```

**Processed Count:** `processed_count:{stageId}` (Integer - incremented by workers)
- Tracks total work units processed for a stage
- Useful for monitoring progress when `PUBLISH_RESULTS=false`

## Useful Redis CLI Commands

Check queue depth for a stage:
```bash
docker exec -it ramsey-redis-1 redis-cli LLEN work_queue:7
```

Peek at first item in queue (without removing):
```bash
docker exec ramsey-redis-1 redis-cli LINDEX work_queue:406 0
```

Peek at first 5 items in queue:
```bash
docker exec ramsey-redis-1 redis-cli LRANGE work_queue:406 0 4
```

List all best result keys:
```bash
docker exec -it ramsey-redis-1 redis-cli KEYS "best_result:*"
```

Get best result for a stage:
```bash
docker exec ramsey-redis-1 redis-cli GET best_result:7
```

Get processed count for a stage (with formatting):
```bash
docker exec ramsey-redis-1 redis-cli GET processed_count:7 | tr -d '"\r' | python3 -c "import sys; val=sys.stdin.read().strip(); print(f'{int(val):,}' if val else 'Key not found')"
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `REDIS_HOST` | Redis server hostname | `localhost` |
| `REDIS_PORT` | Redis server port | `6379` |
| `WORK_ENUMERATION_STRATEGY` | Strategy for work distribution (e.g. `DUAL_EDGE_CARDINALITY`). If empty, uses legacy queue-based mode. | `` |
| `WORK_UNIT_QUEUE_DEPTH_MIN` | Min queue depth (Legacy mode only) | `4000000` |
| `WORK_UNIT_QUEUE_DEPTH_MAX` | Max queue depth (Legacy mode only) | `8000000` |
| `WORK_UNIT_ANALYSIS_TYPE` | Analysis type | `TARGETED` |
| `STAGE_PROGRESSION_FREQ` | How often to check for improvements (ms) | `30000` |

## Image Build and Deploy

Build the image:
```bash
docker build -t benferenchak/ramsey-queue-manager:develop .
```

Publish to Dockerhub:
```bash
docker push benferenchak/ramsey-queue-manager:develop
```

Start with Docker Compose (see `ramsey-mw/docker/ramsey-compose.yml`):
```bash
docker compose -f ./docker/ramsey-compose.yml -p ramsey up --scale ramsey-queue-manager=1 -d
```