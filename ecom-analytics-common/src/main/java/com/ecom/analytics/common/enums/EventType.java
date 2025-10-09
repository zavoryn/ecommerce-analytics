package com.ecom.analytics.common.enums;

/**
 * 用户行为事件类型
 * 对应埋点规范中的 event_name 字段(DRD)
 */
public enum EventType {

    VIEW_PAGE("view_page", "页面浏览"),
    VIEW_ITEM("view_item", "商品曝光/浏览"),
    SEARCH("search", "搜索"),
    CLICK("click", "点击"),
    ADD_CART("add_cart", "加入购物车"),
    CREATE_ORDER("create_order", "创建订单"),
    PAY_ORDER("pay_order", "支付订单"),
    CANCEL_ORDER("cancel_order", "取消订单"),
    REFUND("refund", "退款");

    private final String code;
    private final String desc;

    EventType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() { return code; }
    public String getDesc() { return desc; }

    public static EventType of(String code) {
        for (EventType t : values()) {
            if (t.code.equals(code)) return t;
        }
        return null;
    }
}
