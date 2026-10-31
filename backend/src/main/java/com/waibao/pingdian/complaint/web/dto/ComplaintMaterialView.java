package com.waibao.pingdian.complaint.web.dto;

/**
 * 材料签名入口：前端携带 ticketId 调用 /api/admin/materials/{type}/{id}/url。
 */
public record ComplaintMaterialView(String materialType, String id, String url) {
}
