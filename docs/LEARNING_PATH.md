# Öğrenme sırası

Bu sürümü öğrenirken önce tek bir isteği HTTP girişinden SQL commit'e kadar takip et. Bütün sınıfları aynı anda ezberlemek yerine testleri gözlem noktası olarak kullan.

1. `Config` ve `Main`: ortam değişkenleri, record, nesne oluşturma, dependency injection'ın framework olmadan yapılması, try-with-resources ve graceful shutdown.
2. `ApiException`, `Json`, `ApiServer`: HTTP yöntemi, path, header, durum kodu, JSON doğrulama, UUID ve hata yanıtı.
3. `PasswordHasher`: salt, tek yönlü türetme, iterasyon, constant-time karşılaştırma. Hash ile şifreleme farkını açıkla.
4. `AuthService`: normalize email, PreparedStatement, ResultSet, token hash'i, süre sonu, logout ve kullanıcı/admin rolü.
5. `V1` migration: primary/foreign/unique/check/index, SQL numeric, append-only veri ve deferred trigger.
6. `LedgerService.createAccount/getAccount`: JDBC ile insert/select; erişim kontrolünü neden serviste de yaptığımız.
7. `transfer`, `replay`, `lockAccounts`, `post`: transaction, satır kilidi, deadlock, BigDecimal ve idempotency.
8. `reverse`: eski kaydı değiştirmeden yeni kayıtla düzeltme; audit ve muhasebe anlamı.
9. Entegrasyon testleri: testte eklenen hata trigger'ı ile yarıda kalan işlemin gerçekten rollback olduğunu gör. Bu failpoint üretim kodunda bulunmaz.
10. `V2/V3`, Compose ve runtime rolü: migration kullanıcısı ile günlük uygulama kullanıcısının ayrı olmasının nedeni.

## Küçük deneyler

- Kendi test hesabına `100.00` yükle; `25.45` gönder ve iki bakiyeyi elle doğrula.
- Aynı transferi aynı anahtarla on kez gönder. Tek transfer kimliği ve iki defter satırı görmelisin.
- Aynı anahtarla `25.46` gönder; `409` sonucunu incele.
- Farklı anahtarlı eşzamanlı transferlerin neden eksi bakiye oluşturmadığını testle göster.
- Hesabı dondur; transferin reddedildiğini ve önceki geçmişin korunduğunu gör.
- Başarılı işlemi telafi et; orijinal ve telafi kaydının birlikte kaldığını incele.
- DB'de yalnızca `balance` alanını artırmayı dene; commit kontrolünün bunu reddetmesini açıkla.

## Spring Boot aşaması

Şu an Spring bağımlılığı ekleme. Java, JDBC ve SQL akışı anlaşıldıktan sonra aynı kabul testleri korunarak adım adım taşınabilir:

1. `ApiServer` yerine REST controller, request DTO ve Bean Validation.
2. `Database` ve constructor kurulumları yerine Spring DataSource/DI; migration'lar aynen kalır.
3. Transaction sınırlarını `@Transactional` ile kur; aynı bağlantı ve aynı kilit sırasını koru. JDBC'den JPA'ya geçiş zorunlu değildir.
4. Spring Security ile mevcut rol/oturum kurallarını eşle; token stratejisini gerekçesiz değiştirme.
5. `@ControllerAdvice` ile mevcut hata sözleşmesini koru.
6. Actuator/Micrometer, yapılandırılmış gözlemlenebilirlik, cursor sayfalama ve kontrollü outbox ekle.
7. Banka/ödeme adaptörlerini mutabakat ve benzersiz sağlayıcı işlem kimlikleriyle ayrı bir modül olarak geliştir.

Geçişin başarı ölçütü sadece uygulamanın açılması değildir: mevcut eşzamanlılık, rollback, sahiplik ve idempotency testlerinin aynı iş sonuçlarını vermesidir.
