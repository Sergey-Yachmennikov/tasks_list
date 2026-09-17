package com.example.tasklist.order.persistence;

import com.example.tasklist.order.domain.Order;
import com.example.tasklist.order.domain.OrderItem;
import com.example.tasklist.order.repository.OrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class JdbcOrderRepository implements OrderRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcOrderRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(Order order) {
        // Позиции заказа хранятся как JSONB, а не отдельной таблицей order_item: сам список
        // позиций не имеет отношения к предмету этого модуля (асинхронная развязка аналитики
        // от ответа пользователю) — реляционное моделирование добавило бы только несвязанный
        // с этим репозиторный код.
        String sql = """
                INSERT INTO orders (id, client_id, items, total_amount, created_at)
                VALUES (:id, :clientId, :items::jsonb, :totalAmount, :createdAt)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", order.id())
                .addValue("clientId", order.clientId())
                .addValue("items", writeItems(order.items()))
                .addValue("totalAmount", order.totalAmount())
                .addValue("createdAt", Timestamp.from(order.createdAt()));
        jdbcTemplate.update(sql, params);
    }

    private String writeItems(List<OrderItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            // Сериализация здесь полностью под нашим контролем (обычный record), поэтому
            // ошибка означает баг в коде, а не временное состояние, которое стоит ретраить.
            throw new IllegalStateException("Failed to serialize order items", e);
        }
    }
}
