# Sharding the catalog

One PostgreSQL server can handle a lot, but not everything: at some point its disk, its write throughput or simply
the size of its tables becomes the limit, and a bigger server ("scaling up") gets very expensive and still has a
ceiling. **Sharding** splits the data over several databases ("shards") so that adding a database adds capacity
("scaling out"). YouTube did this with MySQL (and later built Vitess for it); Instagram, Pinterest, Slack and Discord
all shard their main databases.

ITube shards the **catalog** (channels, subscriptions, videos, renditions, upload sessions) because it is the data
every page view reads and every upload writes. The other domains don't need it:

| Domain | Sharded? | Why |
|---|---|---|
| catalog | **yes, by creator**, in catalog-service ([sharding/](../backend/catalog-service/src/main/java/ITubeBackend/sharding)) | grows with every upload, read on every view |
| engagement (history, likes, comments) | already: ScyllaDB partitions every table by user or video id over its nodes automatically (consistent hashing) | the biggest data set; add nodes to grow |
| identity | no | small rows; read replicas and caching are enough |
| payments | no, on purpose | low volume, and money needs one strongly consistent source of truth |

## The shard key: the creator

The most important decision in sharding is the **shard key**: which piece of data decides where a row lives. A good
key keeps the rows that are queried together on the same shard, and spreads the load evenly.

ITube uses the **creator** (the channel owner). A creator's channels, videos, renditions, upload sessions and their
channels' subscriber lists all live on one shard, so:

- a channel page, "my videos", an upload or a visibility change touches **one** database;
- foreign keys (video -> channel) never cross databases;
- creators are spread evenly over shards by a hash of their user id.

The queries that need *everyone's* data (the home feed, channel search, a viewer's own subscriptions) ask every shard
at once and merge the answers ("scatter-gather", below). They are few, and they are heavily cached.

## Ids carry their shard

Every channel and video id says where it lives ([ShardIds](../backend/catalog-service/src/main/java/ITubeBackend/sharding/ShardIds.java)):

```
id = logicalShard << 40 | sequence

     |  12 bits: logical shard 0-4095  |  40 bits: per-database sequence (~1.1 trillion ids)  |
```

So `GET /api/videos/3578910348410883` needs no lookup: `3578910348410883 >> 40 = 3255`, logical shard 3255, which
lives on physical database 1. That is the same trick Instagram and Pinterest use for their ids.

- **Logical vs physical shards.** There are always 4096 *logical* shards (baked into every id). They are mapped onto
  however many *physical* databases you run ([ShardMap](../backend/catalog-service/src/main/java/ITubeBackend/sharding/ShardMap.java)),
  in contiguous ranges. With two databases: logical 0-2047 on database 0, 2048-4095 on database 1. Growing to more
  databases moves whole logical shards; no id ever changes.
- **Browser-safe.** The largest possible id is below 2^52, so it stays exact as a JavaScript number (2^53).
- **Old ids still work.** Ids created before sharding are small numbers, so their shard bits are 0: they belong to
  logical shard 0, which always lives on database 0 (the original database). Nothing had to be migrated.

## Where a creator lives: the shard directory

New creators are placed by a hash of their user id. Creators from before sharding, though, are on logical shard 0
whatever their hash says. The table `catalog.owner_shard` (on database 0) records each creator's logical shard
([OwnerDirectory](../backend/catalog-service/src/main/java/ITubeBackend/sharding/OwnerDirectory.java)):

- filled in when a creator's first channel is created (and back-filled for old creators at startup);
- entries never change, so they are cached in Caffeine for an hour: the directory is read about once per creator per
  pod, not once per request.

This "directory-based" placement is what lets a sharded system absorb existing data and, later, move individual
creators between shards.

## How a query finds its database

[ShardRoutingDataSource](../backend/catalog-service/src/main/java/ITubeBackend/sharding/ShardRoutingDataSource.java)
is one `DataSource` in front of all the databases. Code chooses the database with
[Shards](../backend/catalog-service/src/main/java/ITubeBackend/sharding/Shards.java), and everything inside -
Spring Data repositories, JPQL, transactions - runs against that database without knowing it:

```java
// by id: the shard is in the id
shards.onShardOf(videoId, () -> videoRepository.findAccess(videoId));

// by creator: their channels, videos and uploads are together
shards.onOwnerShard(userId, () -> uploadSessions.findByUserId(userId));

// everyone: every shard in parallel, then merge
shards.scatter(shard -> videoRepository.findFeed(Limit.of(100)))
      .stream().sorted(VideoRow.NEWEST_FIRST).limit(100).toList();
```

Safety rails:

- A transaction is bound to one connection, so it can't span shards. Switching shard inside a transaction throws.
- Once the application has started, a catalog query **without** a chosen shard throws instead of silently reading
  database 0, so a missed routing shows up in tests, not as missing data.
- Transactions are opened *inside* the routing (`TransactionTemplate`), never around it.

## Scatter-gather (the home feed)

The feed wants the 100 newest public videos in the whole catalog. Each shard returns its own newest 100
(`ORDER BY created_at DESC LIMIT 100`, an index scan); merging those lists and keeping the top 100 gives exactly the
global answer. The shards are queried in parallel on virtual threads, so the feed takes as long as the slowest
shard, not the sum.

- **Time limit per shard** (`itube.sharding.scatter-timeout`, 2 s). A shard that is down or slow is skipped and the
  page is built from the others. A partial home page beats an error page; the metric
  `itube.catalog.scatter.partial` counts how often it happens.
- **Cached.** The merged feed is cached for 10 s per pod (Caffeine) and at the CDN, so at peak a shard sees about one
  feed query per pod every 10 s, however many people open the home page.

## New ids on each database

[IdAllocator](../backend/catalog-service/src/main/java/ITubeBackend/sharding/IdAllocator.java) takes the next value
of the id column's own sequence on the target database and adds the logical shard. On the original database the
sequence simply continues after the old ids. Different logical shards on the same database share its sequence; the
shard bits keep their ids apart.

## Schema

Every shard has the same schema ([schema-catalog.sql](../backend/catalog-service/src/main/resources/schema-catalog.sql)),
applied by catalog-service to each database at startup (idempotent). Database 0 first runs
[schema-catalog-migrate.sql](../backend/catalog-service/src/main/resources/schema-catalog-migrate.sql), which moves
tables from before the domain split.

## Configuration

```properties
itube.sharding.shards[0].url=jdbc:postgresql://postgres:5432/itube          # the ORIGINAL database
itube.sharding.shards[1].url=jdbc:postgresql://postgres-shard1:5432/itube
# optional, per database: where its range of logical shards starts (default: an even split)
itube.sharding.shards[1].first-logical-shard=2048
```

Locally the second shard is the `postgres-shard1` container (port 5435); in Kubernetes the `postgres-shard1`
StatefulSet. In production each shard should be its own managed database (for example one RDS instance per shard,
each with a standby in another availability zone and read replicas).

## Adding a database later (resharding)

Say you go from 2 to 4 databases. Logical shards 1024-2047 move from database 0 to new database 2, and 3072-4095 from
database 1 to new database 3:

1. Create the new databases. catalog-service creates the schema on start.
2. Copy the rows of the moving logical shards (`WHERE channel_id >> 40 BETWEEN 1024 AND 2047`, and the matching
   videos, renditions, subscriptions, upload sessions) with logical replication or a dump/restore, and keep them in
   sync until the switch.
3. Briefly stop writes for those creators (or the whole catalog for a few seconds), let replication catch up, and
   deploy the new mapping (`shards[2].first-logical-shard=1024`, ...).
4. Delete the moved rows from the old databases.

No id changes, and the other 3072 logical shards are untouched. That is the reason for having many more logical
shards than databases. Vitess and Citus automate exactly these steps.

## Tests

The catalog's Cucumber scenarios ([sharding.feature](../backend/catalog-service/src/test/resources/features/sharding.feature))
run against **two real in-memory databases**. They check that a creator's videos are stored only on their shard,
that ids carry the right shard, that the feed, search and subscriptions merge results from both shards, and that old
ids route to shard 0.
