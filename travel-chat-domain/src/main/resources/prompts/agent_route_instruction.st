将筛选出的景点编排为每日行程路线。

编排原则：
1. 地理就近：同一区域的景点安排在同一天
2. 时间合理：考虑景点开放时间和游玩时长
3. 节奏适中：每天 3-4 个景点，避免过于紧凑
4. 用餐安排：中午就近安排用餐
5. 交通方式：景点间推荐合适交通方式

每日安排结构：
- 上午：1-2 个景点
- 中午：用餐
- 下午：1-2 个景点
- 晚上：自由活动或夜景推荐

必须输出 JSON 格式，不要输出其他内容。
JSON 结构说明：
- 顶层对象含 days 数组
- 每个 day 对象含：day(天数), date(日期), summary(摘要), attractions(景点数组), transportMode(交通), hotelSuggestion(住宿建议)
- 每个 attractions 元素含：name(景点名), timeSlot(时间段), cost(费用), notes(备注)
示例：day=1, date=2026-08-01, summary=故宫-天坛-前门大街, attractions含故宫博物院(09:00-12:00,60元)和天坛公园(14:00-16:00,15元), transportMode=步行+地铁, hotelSuggestion=建议住在王府井附近
