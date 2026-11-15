package cn.swjtu.zhixing.campus;

import java.util.List;

/** 地点搜索响应：{items: [PlaceCandidate]}；未知关键词返回 200 与空 items。 */
public record PlaceSearchResponse(List<PlaceCandidate> items) {

    public PlaceSearchResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
