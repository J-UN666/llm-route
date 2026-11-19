package cn.swjtu.zhixing.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Emits ordinary model tool calls for the documented offline scenarios. */
@Component
@Profile("demo")
class DemoToolCallingClient implements ToolCallingClient {
    private final ObjectMapper mapper;

    DemoToolCallingClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ToolCallingResponse complete(ToolCallingRequest request) {
        String input = request.messages().stream().filter(message -> message.role().equals("user"))
                .map(ToolCallingMessage::content).findFirst().orElse("");
        String text = Normalizer.normalize(input, Normalizer.Form.NFKC).replaceAll("\\s+", "");
        List<ModelToolCall> calls = new ArrayList<>();
        String origin = mentioned(text, "图书馆", "天佑斋1号楼", "天佑斋");
        String destination = mentioned(text, "天府广场", "春熙路", "成都东客站", "成都站", "宽窄巷子");
        if (origin != null) {
            calls.add(call("origin", AssistantTools.SEARCH, Map.of("keyword", origin, "scope", "CAMPUS")));
        }
        if (destination != null) {
            calls.add(call("destination", AssistantTools.SEARCH, Map.of("keyword", destination, "scope", "CITY")));
        }

        Map<String, Object> options = new HashMap<>();
        if (text.contains("少走路") || text.contains("少步行")) {
            options.put("preference", "LESS_WALKING");
        } else if (text.contains("少换乘")) {
            options.put("preference", "FEWER_TRANSFERS");
        } else if (text.contains("推荐")) {
            options.put("preference", "RECOMMENDED");
        }
        if (text.contains("现在出发")) {
            options.put("departNow", true);
        }
        if (calls.isEmpty() && options.isEmpty() && !text.contains("重新查询")) {
            throw new ToolCallingException(422, "DEMO_INPUT_UNSUPPORTED",
                    "离线演示请试试“从图书馆去天府广场，少走路”，再说“改去春熙路”。", false);
        }
        calls.add(call("journey", AssistantTools.PLAN, options));
        return new ToolCallingResponse(null, calls);
    }

    private ModelToolCall call(String id, String name, Map<String, Object> arguments) {
        return new ModelToolCall("demo-" + id, name, mapper.valueToTree(arguments).toString());
    }

    private static String mentioned(String text, String... names) {
        for (String name : names) {
            if (text.contains(name)) {
                return name;
            }
        }
        return null;
    }
}
