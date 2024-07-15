package at.rocworks.gateway.logger.influx

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise

import io.vertx.core.json.JsonObject
import java.util.concurrent.TimeUnit

import org.influxdb.BatchOptions
import org.influxdb.InfluxDB
import org.influxdb.InfluxDBFactory
import org.influxdb.dto.*


class InfluxDBLogger(config: JsonObject) : LoggerBase(config) {
    private val url = config.getString("Url", "")
    private val username = config.getString("Username", "")
    private val password = config.getString("Password", "")
    private val database = config.getString("Database", "test")

    private val session: InfluxDB = if (username == null || username == "")
        InfluxDBFactory.connect(url)
    else
        InfluxDBFactory.connect(url, username, password)

    override fun open(): Future<Unit> {
        val result = Promise.promise<Unit>()
        try {
            val response: Pong = session.ping()
            if (!response.isGood) {
                result.complete()
            } else {
                session.setLogLevel(InfluxDB.LogLevel.NONE)
                session.query(Query("CREATE DATABASE $database"))
                session.setDatabase(database)
                var options = BatchOptions.DEFAULTS
                options = options.bufferLimit(writeParameterBlockSize)
                session.enableBatch(options)
                logger.info("InfluxDB connected.")
                result.complete()
            }
        } catch (e: Exception) {
            logger.severe("InfluxDB connect failed! [${e.message}]")
            result.fail(e)
        }
        return result.future()
    }

    override fun close(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        session.close()
        promise.complete()
        return promise.future()
    }

    private fun influxPointOf(dp: DataPoint): Point {
        val point = Point.measurement(dp.topic.systemName) // TODO: configurable measurement name
            .time(dp.value.sourceTime().toEpochMilli(), TimeUnit.MILLISECONDS)
            .tag("tag", dp.topic.getBrowsePathOrNode().toString()) // TODO: add topicName, topicType, topicNode, ...
            .tag("address", dp.topic.topicNode)
            .tag("status", dp.value.statusAsString())

        val numeric: Double? = dp.value.valueAsDouble()
        if (numeric != null) {
            //logger.debug("topic [$topic] numeric [$numeric]")
            point.addField("value", numeric)
        } else {
            //logger.debug("topic [$topic] text [${value.valueAsString()}]")
            point.addField("text", dp.value.valueAsString())
        }

        return point.build()
    }

    override fun writeExecutor() {
        val batch = BatchPoints.database(database).build()
        pollDatapointBlock {
            batch.point(influxPointOf(it))
        }
        if (batch.points.size > 0) {
            try {
                session.write(batch)
                valueCounterOutput+=batch.points.size
            } catch (e: Exception) {
                logger.severe("Error writing batch [${e.message}]")
            }
        }
    }

    override fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeMS: Long,
        toTimeMS: Long,
        result: (Boolean, List<List<Any?>>?) -> Unit
    ) {
        val fromTimeNano = fromTimeMS * 1_000_000
        val toTimeNano = toTimeMS * 1_000_000
        try {
            val sql = """
                SELECT time, servertime, value, text, status 
                FROM "$system" 
                WHERE "address" = '$nodeId' 
                AND time >= $fromTimeNano AND time <= $toTimeNano 
                """.trimIndent()
            val data = session.query(Query(sql)).let { query ->
                query.results.getOrNull(0)
                    ?.series?.getOrNull(0)
                    ?.values?.map {
                        listOf(
                            it.component1(),
                            it.component2(),
                            it.component3() ?: it.component4(),
                            it.component5()
                        )
                    }
            }
            result(data != null, data)
        } catch (e: Exception) {
            logger.severe("Error executing query [${e.message}]")
            result(false, null)
        }
    }
}