package com.dart.perf;

import com.dart.util.VectorUtils;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis Stack HNSW 向量索引检索路径（对比 MySQL 全量扫描）。
 * 索引 key 格式 dart:chunk:{itemId}:{chunkIndex}（EmbeddingService 双写）。
 * 用 Lettuce 原生 dispatch + NestedMultiOutput（FT.SEARCH 响应含数字与嵌套列表，
 * Spring 的 ByteArrayOutput 不支持整数会抛异常）。
 */
public class RedisVectorSearcher {

    private static final Logger log = LoggerFactory.getLogger(RedisVectorSearcher.class);

    public static final String INDEX = "idx:knowledge_vector";
    private static final String CREATE_CMD = "FT.CREATE " + INDEX
            + " ON HASH PREFIX 1 dart:chunk: SCHEMA embedding VECTOR HNSW 6"
            + " DIM 1024 DISTANCE_METRIC COSINE TYPE FLOAT32";

    private static final ProtocolKeyword FT_SEARCH = keyword("FT.SEARCH");
    private static final ProtocolKeyword FT_LIST = keyword("FT._LIST");
    private static final ProtocolKeyword FT_CREATE = keyword("FT.CREATE");
    private static final ProtocolKeyword FT_INFO = keyword("FT.INFO");

    private static ProtocolKeyword keyword(String name) {
        return new ProtocolKeyword() {
            @Override
            public byte[] getBytes() {
                return name.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    private final StatefulRedisConnection<String, String> connection;

    public RedisVectorSearcher(RedisConnectionFactory factory) {
        LettuceConnectionFactory lcf = (LettuceConnectionFactory) factory;
        RedisClient client = RedisClient.create("redis://" + lcf.getHostName() + ":" + lcf.getPort());
        this.connection = client.connect(StringCodec.UTF8);
    }

    private RedisCommands<String, String> sync() {
        return connection.sync();
    }

    /** 索引不存在则创建（Redis Stack 启动后首次调用） */
    public void ensureIndex() {
        List<String> indexes = executeList();
        if (indexes == null || !indexes.contains(INDEX)) {
            CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8);
            for (String token : CREATE_CMD.replace("FT.CREATE ", "").split(" ")) {
                args.add(token);
            }
            sync().dispatch(FT_CREATE, new NestedMultiOutput<>(StringCodec.UTF8), args);
            log.info("已创建 Redis 向量索引: {}", INDEX);
        }
    }

    /**
     * KNN 检索：返回按余弦距离升序的 chunk 记录（docKey 提取 itemId/chunkIndex）。
     * 查询计时由调用方负责；本方法仅检索不回查 MySQL。
     */
    public List<ChunkHit> search(float[] queryVec, int topK) {
        byte[] vecBytes = VectorUtils.floatArrayToBytes(queryVec);
        String query = "*=>[KNN " + topK + " @embedding $vec AS score]";
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8);
        args.add(INDEX).add(query)
                .add("PARAMS").add("2").add("vec")
                .add(vecBytes)
                .add("SORTBY").add("score").add("DIALECT").add("3");
        Object result = sync().dispatch(FT_SEARCH, new NestedMultiOutput<>(StringCodec.UTF8), args);
        if (java.lang.System.getProperty("perf.debug") != null) {
            System.out.println("[DEBUG] FT.SEARCH 原始响应: " + result);
        }
        return parseResult(result);
    }

    @SuppressWarnings("unchecked")
    private List<ChunkHit> parseResult(Object result) {
        List<ChunkHit> hits = new ArrayList<>();
        if (!(result instanceof List<?> root)) {
            return hits;
        }
        // Redis Stack 对 KNN 返回 DIALECT 3 格式：["attributes",[],"format","STRING","results",
        //   [["id",docKey,"extra_attributes",[score,..,chunk_id,..]],...]]
        Object docs = findValue(root, "results");
        if (docs == null) {
            // 旧格式：直接 [count, [docKey, fields], ...]
            docs = root;
        }
        if (!(docs instanceof List<?> docList)) {
            return hits;
        }
        for (Object entry : docList) {
            if (!(entry instanceof List<?> doc)) {
                continue;
            }
            // DIALECT 3 doc = [id, docKey, extra_attributes, fields]；旧格式 doc = [docKey, fields]
            String docKey;
            Object fields;
            if (doc.size() >= 4 && "extra_attributes".equals(String.valueOf(doc.get(2)))) {
                docKey = String.valueOf(doc.get(1));
                fields = doc.get(3);
            } else if (doc.size() >= 2) {
                docKey = String.valueOf(doc.get(0));
                fields = doc.get(1);
            } else {
                continue;
            }
            ChunkHit hit = parseKey(docKey);
            if (hit == null) {
                continue;
            }
            hit = hit.withScore(extractScore(fields));
            hits.add(hit);
        }
        return hits;
    }

    /** 在交替 key/value 列表中查找指定 key 的值 */
    private static Object findValue(List<?> list, String key) {
        for (int i = 0; i + 1 < list.size(); i++) {
            if (key.equals(String.valueOf(list.get(i)))) {
                return list.get(i + 1);
            }
        }
        return null;
    }

    private static Double extractScore(Object fieldList) {
        if (!(fieldList instanceof List<?> fields)) {
            return null;
        }
        Object v = findValue(fields, "score");
        if (v instanceof Number num) {
            return num.doubleValue();
        }
        // RESP2 无 double 类型：KNN 距离以 bulk string（如 "0.123"）返回
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** docKey = dart:chunk:{itemId}:{chunkIndex} → itemId/chunkIndex */
    static ChunkHit parseKey(String docKey) {
        String prefix = "dart:chunk:";
        if (docKey == null || !docKey.startsWith(prefix)) {
            return null;
        }
        String[] parts = docKey.substring(prefix.length()).split(":");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new ChunkHit(Long.parseLong(parts[0]), Integer.parseInt(parts[1]), null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** @param distance 余弦距离（KNN score）；null 表示响应未带 score */
    public record ChunkHit(long itemId, int chunkIndex, Double distance) {
        ChunkHit withScore(Double distance) {
            return new ChunkHit(itemId, chunkIndex, distance);
        }

        /** 余弦距离 → 相似度；无 score 时返回 -1（不参与指标） */
        public double similarity() {
            return distance == null ? -1.0 : 1.0 - distance;
        }
    }

    /** 索引内文档数（FT.INFO num_docs） */
    public long indexDocCount() {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8);
        args.add(INDEX);
        Object result = sync().dispatch(FT_INFO, new NestedMultiOutput<>(StringCodec.UTF8), args);
        if (!(result instanceof List<?> list)) {
            return -1;
        }
        for (int i = 0; i + 1 < list.size(); i++) {
            if ("num_docs".equals(String.valueOf(list.get(i))) && list.get(i + 1) instanceof Number n) {
                return n.longValue();
            }
        }
        return -1;
    }

    /** 全量计数（DIALECT 3 results 列表大小） */
    public long countAll() {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8);
        args.add(INDEX).add("*");
        Object result = sync().dispatch(FT_SEARCH, new NestedMultiOutput<>(StringCodec.UTF8), args);
        if (result instanceof List<?> list) {
            Object docs = findValue(list, "results");
            if (docs instanceof List<?> docList) {
                return docList.size();
            }
        }
        return -1;
    }

    private List<String> executeList() {
        Object result = sync().dispatch(FT_LIST, new NestedMultiOutput<>(StringCodec.UTF8));
        if (!(result instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }
}
