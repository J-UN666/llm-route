package cn.swjtu.zhixing.campus;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地点接口：
 * <ul>
 *   <li>GET /api/places?keyword=图书馆&amp;scope=CAMPUS —— 返回 {items:[PlaceCandidate]}，未知关键词 200 + 空 items；</li>
 *   <li>GET /api/places/{id} —— 返回 PlaceCandidate，未知 ID 404 + PLACE_NOT_FOUND。</li>
 * </ul>
 * 契约见 docs/api.md。
 */
@RestController
@RequestMapping("/api/places")
public class PlaceController {

    private final PlaceService placeService;

    public PlaceController(PlaceService placeService) {
        this.placeService = placeService;
    }

    @GetMapping
    public PlaceSearchResponse search(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "scope", required = false) String scope) {
        return new PlaceSearchResponse(placeService.search(keyword, scope));
    }

    @GetMapping("/{id}")
    public PlaceCandidate getById(@PathVariable("id") String id) {
        return placeService.getById(id);
    }
}
