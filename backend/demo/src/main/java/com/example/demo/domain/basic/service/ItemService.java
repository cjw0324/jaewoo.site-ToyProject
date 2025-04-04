package com.example.demo.domain.basic.service;


import com.example.demo.domain.basic.dto.ItemCreateRequest;
import com.example.demo.domain.basic.dto.ItemRequestDto;
import com.example.demo.domain.basic.dto.ItemResponseDto;
import com.example.demo.domain.basic.entity.Item;
import com.example.demo.domain.basic.entity.ItemImage;
import com.example.demo.domain.basic.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItemService {
    private final ItemRepository itemRepository;
    private final RedissonClient redissonClient;
    private final RedisTemplate<String, Object> redisTemplate;

    public void likeItem(Long itemId) {
        String lockKey = "lock:item:" + itemId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // 분산 락 획득 (10초 후 자동 해제)
            lock.lock(10, TimeUnit.SECONDS);

            // 캐시 키 생성
            String cacheKey = "item:like:" + itemId;
            Long cachedLikeCount = (Long) redisTemplate.opsForValue().get(cacheKey);

            // 데이터베이스에서 Item 조회 (없으면 예외 발생)
            Item item = itemRepository.findById(itemId)
                    .orElseThrow(() -> new RuntimeException("Item not found with id: " + itemId));

            if (cachedLikeCount == null) {
                cachedLikeCount = item.getLike_count();
            }

            // 좋아요 수 증가
            cachedLikeCount++;

            // 캐시 업데이트
            // likeItem 메서드 내
            redisTemplate.opsForValue().set(cacheKey, cachedLikeCount, 10, TimeUnit.MINUTES);

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public Long getLikeCount(Long itemId) {
        String cacheKey = "item:like:" + itemId;
        Long cachedLikeCount = (Long) redisTemplate.opsForValue().get(cacheKey);
        if (cachedLikeCount == null) {
            Item item = itemRepository.findById(itemId)
                    .orElseThrow(() -> new RuntimeException("Item not found with id: " + itemId));
            cachedLikeCount = item.getLike_count();
            // getLikeCount 메서드 내
            redisTemplate.opsForValue().set(cacheKey, cachedLikeCount, 10, TimeUnit.MINUTES);

        }
        return cachedLikeCount;
    }

    @Transactional
    public ItemResponseDto createItem(ItemCreateRequest request) {
        Item item = new Item();
        item.setItem_name(request.getItemName());
        item.setLike_count(0L); // 기본값

        // 첫 번째 이미지를 대표 이미지로 설정 (선택사항)
        if (!request.getImageUrls().isEmpty()) {
            item.setImageUrl(request.getImageUrls().get(0));
        }

        for (String url : request.getImageUrls()) {
            ItemImage image = new ItemImage();
            image.setImageURL(url);
            item.addImage(image); // 연관관계 편의 메서드 사용
        }

        Item saved = itemRepository.save(item);
        return toDto(saved);
    }

    public List<ItemResponseDto> getAllItems() {
        return itemRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public ItemResponseDto getItem(Long id) {
        return itemRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));
    }

    @Transactional
    public ItemResponseDto updateItem(Long id, ItemRequestDto dto) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));

        item.setItem_name(dto.getItemName());

        // 대표 이미지 업데이트
        if (dto.getImageUrls() != null && !dto.getImageUrls().isEmpty()) {
            item.setImageUrl(dto.getImageUrls().get(0));
        }

        // 기존 이미지 비우기
        item.getImages().clear();

        // 새 이미지 추가
        for (String url : dto.getImageUrls()) {
            ItemImage newImage = new ItemImage();
            newImage.setImageURL(url);
            item.addImage(newImage); // 연관관계 메서드 사용
        }

        return toDto(itemRepository.save(item));
    }


    @Transactional
    public ItemResponseDto deleteItem(Long id) {
        ItemResponseDto deleteItemDto = itemRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));

        itemRepository.deleteById(deleteItemDto.getItemId());
        return deleteItemDto;
    }


    private ItemResponseDto toDto(Item item) {
        ItemResponseDto dto = new ItemResponseDto();
        dto.setItemId(item.getItem_id());
        dto.setItemName(item.getItem_name());
        dto.setLikeCount(item.getLike_count());

        List<String> imageUrls = item.getImages().stream()
                .map(ItemImage::getImageURL)
                .toList();
        dto.setImageUrls(new ArrayList<>(imageUrls));

        return dto;
    }


    @Scheduled(fixedRate = 300000) // 5분마다 실행
    public void syncLikeCountFromRedisToDb() {
        Set<String> keys = redisTemplate.keys("item:like:*");
        if (keys == null) return;

        for (String key : keys) {
            try {
                Long itemId = Long.parseLong(key.split(":")[2]);
                Long likeCount = (Long) redisTemplate.opsForValue().get(key);
                if (likeCount != null) {
                    Item item = itemRepository.findById(itemId)
                            .orElseThrow(() -> new RuntimeException("Item not found with id: " + itemId));
                    item.setLike_count(likeCount);
                    itemRepository.save(item);
                }
            } catch (Exception e) {
                // 로깅 또는 에러 처리
                System.err.println("Failed to sync like count for key: " + key);
            }
        }
    }
}
