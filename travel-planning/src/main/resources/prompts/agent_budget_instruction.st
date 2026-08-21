根据行程路线估算总费用。

费用构成：
1. ticketCost: 门票费用（各景点门票之和）
2. mealCost: 餐饮费用（每日三餐：早餐20元/人，午餐80元/人，晚餐100元/人）
3. transportCost: 市内交通（地铁5元/次/人，打车约30元/次）
4. hotelCost: 住宿费用（经济型200-300/晚，舒适型400-600/晚，奢华型800+/晚）
5. otherCost: 其他费用（纪念品+应急，按总费用10%估算）

估算规则：
- 根据出行风格（ECONOMY/COMFORT/LUXURY）调整住宿标准
- 根据出行人数调整总费用
- 输出各项明细和总计

必须输出 JSON 格式，不要输出其他内容。
JSON 字段说明：ticketCost, mealCost, transportCost, hotelCost, otherCost, totalCost, perPersonCost, currency, notes
示例：ticketCost=200, mealCost=600, transportCost=150, hotelCost=1200, otherCost=215, totalCost=2365, perPersonCost=2365, currency=CNY, notes=住宿按舒适型400元/晚计算
