# Çalıştırma ve işletim

## Ayarlar

`DATABASE_URL` bir JDBC PostgreSQL adresidir. `DATABASE_USER` ve `DATABASE_PASSWORD` zorunludur. Boş parola üretim başlatmasında kabul edilmez. JDK `21+` kullan. `.env` kaynak kontrolüne girmez; secret manager veya işletim ortamı değişkenleri dağıtım için uygundur.

`DB_MIGRATE=true` açılışta migration uygular. `false` mevcut migration sürümünü doğrular, eksik/güncel olmayan şemada açılmaz. `java -jar target/ledgerline.jar migrate` yalnızca migration çalıştırıp çıkar. Yeni migration'ı eski SQL dosyasını değiştirerek değil yeni `V4__...sql` dosyası ekleyerek geliştir.

`DB_POOL_SIZE` varsayılan 12, aralık 2–64'tür. Her JVM için bu kadar PostgreSQL bağlantısı ayrılabilir. Bağlantı alma 5 s, SQL 10 s, satır/advisory kilit bekleme 5 s ile sınırlıdır. DB bağlantı hatasında finansal isteği aynı idempotency anahtarıyla tekrar dene.

`HTTP_HOST` varsayılan `127.0.0.1`, `HTTP_PORT` 8080'dir. HTTP iş kuyruğu ve thread sayısı sınırlıdır. HTTPS uygulama önündeki proxy/load balancer'da sonlandırılmalıdır. API herkese açık CORS izni vermez. Tarayıcı istemcisi eklenirse belirli origin'lerle bir politika tanımlanmalıdır.

## Runtime rolünü ayırma

Compose ilk DB kurulumunda `ledgerline_migrator` ve `ledgerline_app` rollerini ayırır. Migration servisi şema sahibidir; HTTP süreci `ledgerline_app` kullanır. Yerel Docker migration rolü PostgreSQL imajının kurulum hesabı olduğundan güçlüdür; dışa açılan bir DB'de superuser kullanmak yerine yalnızca kendi şemasına sahip bir deployment rolü kullan.

Mevcut PostgreSQL'e elle kurulumda yönetici olarak `ledgerline_app` rolünü **migration'dan önce** oluştur ve projenin şemasında `USAGE` ver. Parolayı güvenli araçla belirle. Örneğin `psql` içindeki `\password ledgerline_app` parola girişini komut geçmişinden uzak tutar. `CREATE` yetkisini PUBLIC'ten kaldır. Şema sahibiyle migration çalıştır; V2, uygulama rolüne yalnızca gereken tablo/sütun/sequence izinlerini verir. Sonra API'nin ayarlarını `DATABASE_USER=ledgerline_app` ve `DB_MIGRATE=false` yap.

V2 uygulandıktan sonra rolü oluşturursan V2 tekrar çalışmaz; V2'deki grant işlemlerini şema sahibi olarak ayrıca uygula veya yeni migration yaz. Migration geçmişini silme ve clean kullanma. Trigger'lar runtime rolünün schema sahibi olmamasına dayanır; DB yöneticisi her zaman güven sınırının içindedir.

## Ters proxy ve hız sınırı

Doğrudan bağlantıda socket IP kullanılır; istemcinin gönderdiği `X-Forwarded-For` ve `X-Real-IP` dikkate alınmaz. Bir proxy'nin arkasında çalıştırırken `TRUSTED_PROXY_IPS` değişkenine yalnızca API'nin bağlantıyı gördüğü proxy IP'lerini virgülle yaz. Hostname ve CIDR desteklenmez. IPv4/IPv6 literal kullanılır.

Güvenilen proxy her istekte `X-Real-IP` alanını gerçek istemci IP'siyle **üzerine yazarak** göndermeli. Güvenilen proxy'den eksik/çoklu/geçersiz başlık gelirse giriş/kayıt reddedilir. Nginx için ilgili location içinde `proxy_set_header X-Real-IP $remote_addr;` kullanılabilir. API portunu proxy dışındaki istemcilere açma; aksi halde güvenilen IP'den doğrudan erişebilen biri başlığı taklit edebilir.

Giriş: normalize e-posta başına 10 ve IP başına 60 deneme/15 dakika. Kayıt: e-posta başına 5 ve IP başına 30 deneme/15 dakika. Başarısız ve başarılı girişler sayılır; pencere süresi dolduğunda yenilenir. Limitler işlem başına ayrı namespace'lerdedir. `429` yanıtında `Retry-After: 900` üst bekleme sınırıdır. Çok kullanıcılı NAT ve yüksek trafik için limitleri ürün gereksinimleriyle yeniden değerlendir; proxy'de ek trafik/boyut sınırı uygula.

## Sağlık ve kapanış

`GET /health/live` süreç yanıtını, `/health/ready` bağlantı ve hesap tablosuna erişimi kontrol eder. Finansal denge kontrolü admin yetkili `/v1/admin/reconciliation` çağrısıdır. Bu tek SQL snapshot'ında hesap/defter eşitliğini denetler.

Ctrl+C/SIGTERM sırasında HTTP yeni işleri durdurur, mevcut istekler için kısa bir tamamlama aralığı tanır ve DB havuzunu kapatır. Container 20 s kapanış süresi verir. Sunucu zorla öldürülürse PostgreSQL commit edilmemiş transaction'ları geri alır; commit edilmiş yanıt kaybolduysa idempotency tekrar denemeyi güvenli yapar.

## Yedekleme ve bakım

PostgreSQL için düzenli mantıksal yedek, geri yükleme denemesi ve ihtiyaca göre WAL/PITR politikası belirle. Docker volume bir yedek değildir. Finansal tabloları ve kalıcı idempotency anahtarlarını normal bakımda silme. Süresi geçmiş session ve auth pencereleri sınırlı partiler halinde giriş/kayıt sırasında temizlenir; uzun süre trafik yoksa bakım işi eklenebilir.

Docker'da `docker compose down` servisleri durdurur, volume kalır. `down -v` verileri siler; geliştirme ortamını sıfırlamak dışında kullanma. İlk volume oluşturulduktan sonra `.env` parolasını değiştirmek PostgreSQL rol parolasını değiştirmez; DB'de parola rotasyonu da gerekir.

Bu teslimatta yerel Java/HTTP/PostgreSQL yolları çalıştırıldı. Docker motoru bu ortamda çalışmadığı için container build/up yolu çalıştırılmadı; Compose ve Dockerfile ayrıca sözdizimsel olarak kontrol edildi. CI dosyası hazırdır, henüz GitHub'da çalıştırılmadı.
