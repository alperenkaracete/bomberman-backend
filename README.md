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

## Railway'e deploy

Repo `Dockerfile` ile derlenir. Railway `PORT` değişkenini çalışma anında verir, uygulama onu
`application.properties` üzerinden okur. Frontend `wss://<railway-alan-adı>/oyun-odasi` adresine bağlanır.

## Ortam değişkenleri

| Değişken          | Varsayılan                                                      | Açıklama |
|-------------------|-----------------------------------------------------------------|----------|
| `PORT`            | `8080`                                                          | Dinlenecek port (Railway otomatik verir). `application.properties`: `server.port=${PORT:8080}` |
| `ALLOWED_ORIGINS` | `https://karacete.com,http://localhost:*,http://127.0.0.1:*`    | Virgülle ayrılmış izinli origin listesi; `*` joker desteklenir (örn. `http://localhost:*`). |
| `HEARTBEAT_INTERVAL_SECONDS` | `15`                                                 | Sunucunun her bağlantıya WebSocket ping frame'i gönderme aralığı (sn). |
| `HEARTBEAT_TIMEOUT_SECONDS`  | `45`                                                 | Bu süre boyunca pong ya da herhangi bir mesaj gelmeyen oturum kapatılır (sn). |
| `EMPTY_ROOM_TTL_SECONDS`     | `120`                                                | Son oyuncu çıkınca odanın silinmeden önce bekletileceği süre (sn). |

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
- Oda kodu 6 karakterdir: büyük harf + rakam, karışan `0/O/1/I` hariç. Boş kalan oda hemen silinmez, bkz. [Yeniden bağlanma](#4-yeniden-bağlanma-ve-bağlantı-sağlığı).
- `join_room` kodu büyük/küçük harf duyarsızdır. Odada zaten kayıtlı bir `id` ile `join_room` gelirse yeni oturum eskisinin yerini alır
  (bkz. [Yeniden bağlanma](#4-yeniden-bağlanma-ve-bağlantı-sağlığı)).

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

### 4. Yeniden bağlanma ve bağlantı sağlığı

- **Aynı kimlikle soket devralma:** odada zaten bir oturumla kayıtlı `id` ile `join_room` gelirse (eski oturum
  hâlâ açık görünse bile) yeni oturum eskisinin yerini alır. Eski oturum `4000 replaced` koduyla kapatılır ve
  `player_disconnect` **yayınlanmaz**; oyuncu listedeki sırasını korur. Yeni oturum `room_joined` alır, odadaki
  diğerleri normal bir katılım gibi `player_joined` alır (liderler böylece tam durumu yeniden gönderebilir).
  Eski oturumun kapanışı yeni oturumu odadan çıkarmaz. Başka odadaki aynı `id` etkilenmez.
- **Heartbeat:** sunucu her bağlantıya `HEARTBEAT_INTERVAL_SECONDS` (15 sn) aralığıyla WebSocket ping frame'i
  gönderir (tarayıcılar otomatik pong döner). `HEARTBEAT_TIMEOUT_SECONDS` (45 sn) boyunca pong ya da herhangi
  bir mesaj gelmeyen oturum kapatılır; normal kapanış yolundan geçtiği için odadakilere `player_disconnect` gider.
- **Boş oda toleransı:** son oyuncu çıkınca oda hemen silinmez; `EMPTY_ROOM_TTL_SECONDS` (120 sn) boyunca aynı
  kod ve kapasiteyle kodla bulunabilir kalır. Bu sürede biri katılırsa oda devam eder; kimse dönmezse silinir
  (sonrasında `ROOM_NOT_FOUND`).

## Bilinen sınırlamalar

- Oyuncu kimlikleri odadaki herkese görünür, bu yüzden aynı kimlikle soket devralma güvenilir arkadaş grupları
  içindir; odadaki biri başkasının `id`'siyle katılarak onun oturumunu düşürebilir.
