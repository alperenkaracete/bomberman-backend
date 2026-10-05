# Bomberman Backend — oyundan bağımsız oda sunucusu

Karacete oyun sitesi için Spring Boot WebSocket sunucusu. Sunucu **oyun mantığı bilmez**: sadece oda
kurar/yönetir ve odadaki oyuncular arasında mesajları olduğu gibi iletir. Yeni bir oyun eklemek
yalnızca frontend işidir.

## Çalıştırma

Gereksinimler: Java 21, Maven 3.9+.

```bash
mvn spring-boot:run          # http://localhost:8080, WebSocket: ws://localhost:8080/oyun-odasi
mvn test                     # birim testleri
docker build -t bomberman-backend . && docker run -p 8080:8080 bomberman-backend
```

## Ortam değişkenleri

| Değişken          | Varsayılan                                                      | Açıklama |
|-------------------|-----------------------------------------------------------------|----------|
| `PORT`            | `8080`                                                          | Dinlenecek port (Railway otomatik verir). `application.properties`: `server.port=${PORT:8080}` |
| `ALLOWED_ORIGINS` | `https://karacete.com,http://localhost:*,http://127.0.0.1:*`    | Virgülle ayrılmış izinli origin listesi; `*` joker desteklenir (örn. `http://localhost:*`). |

## Mesaj protokolü

Tüm mesajlar JSON metindir. Bağlantı başına tek oda vardır. Mesaj boyut sınırı **64 KB**
(aşılırsa bağlantı 1009 koduyla kapatılır).

### 1. Odaya girmek (bağlandıktan sonraki ilk mesaj)

İstemci → sunucu:

```json
{"type":"create_room","id":"<oyuncu id>","name":"<takma ad>","game":"bomberman","maxPlayers":4}
{"type":"join_room","room":"ABC123","id":"<oyuncu id>","name":"<takma ad>"}
```

- `maxPlayers` isteğe bağlıdır (varsayılan 4, izinli aralık 2–8). `id` ve `game` zorunlu, en fazla 32 karakter;
  `name` en fazla 32 karakter (boşsa `Player`).
- Oda kodu 6 karakterdir: büyük harf + rakam, karışan `0/O/1/I` hariç. Boş kalan oda silinir.
- `join_room` kodu büyük/küçük harf duyarsızdır. Aynı odada aynı oyuncu `id` iki kez kullanılamaz.

Sunucu → istemci:

| Mesaj | Kime | Ne zaman |
|-------|------|----------|
| `{"type":"room_created","room":"ABC123"}` | oluşturan | `create_room` sonrası |
| `{"type":"room_joined","room":"ABC123","game":"...","players":[{"id":"...","name":"..."}]}` | katılan | `join_room` sonrası; `players` kendisi dahil tüm oyuncular |
| `{"type":"player_joined","id":"...","name":"..."}` | odadaki diğerleri | biri katılınca |
| `{"type":"player_disconnect","id":"<id>"}` | odadaki kalanlar | biri bağlantıyı kesince |

### 2. Hatalar

```json
{"type":"error","code":"ROOM_NOT_FOUND | ROOM_FULL | NOT_IN_ROOM | BAD_REQUEST"}
```

- `ROOM_NOT_FOUND`: oda yok · `ROOM_FULL`: oda dolu
- `NOT_IN_ROOM`: odaya girmeden oda komutu dışında bir mesaj gönderildi
- `BAD_REQUEST`: geçersiz JSON, eksik/geçersiz alan, odaya girmeden önce yanlış biçimli istek

### 3. Mesaj iletimi

Odaya girdikten sonra istemcinin gönderdiği **her mesaj** (örn. `move`, `bomb`, `map_update`,
`player_death`, `restart_vote`) hiç değiştirilmeden, gönderen hariç aynı odadaki oyunculara iletilir.
Sunucu içeriği ayrıştırmaz veya doğrulamaz.
