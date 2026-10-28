# Doğrulama raporu

28 Ekim 2026 tarihinde Java 21.0.11, Maven 3.9.16 ve gerçek PostgreSQL 18.6 ile temiz derleme yapıldı. GitHub sürümü ve Türkçe açıklamalı sürüm ayrı ayrı aynı testlerden geçti.

- PasswordHasherTest: 2 birim testi.
- AuthSecurityIT: 8 PostgreSQL kimlik doğrulama ve hız sınırı testi.
- HttpApiIT: 9 gerçek HTTP ve PostgreSQL testi.
- LedgerIntegrationIT: 19 finansal işlem ve eşzamanlılık testi.
- ProxySecurityIT: 8 gerçek HTTP proxy güvenliği testi.

Her sürümde toplam **46 test, 0 başarısızlık, 0 hata, 0 atlanan test**.

Doğrulama komutu: `mvn clean -Pintegration verify`. Testler ayrı yerel PostgreSQL sunucusunda rastgele oluşturulan şemaları kullandı; uygulama verileri ve mevcut kullanıcı veritabanları kullanılmadı.

Kapsam: aynı anahtarın eşzamanlı tekrarı, farklı içerikle anahtar çakışması, yetersiz bakiye, karşılıklı eşzamanlı transferler, yarıda zorlanan SQL hatasında rollback, çift taraflı defter ve SQL kısıtları, tek seferlik telafi, harcanmış paranın telafisinin reddi, benzersiz dış tahsilat kimliği, hesap sahipliği, dondurma/kapatma, oturum iptali/süre sonu, parola türetme, kalıcı hız sınırları ve güvenilen proxy doğrulaması.

Paketlenmiş JAR ayrıca kısıtlı `ledgerline_app` rolüyle çalıştırıldı. Kayıt, giriş, hesap açma, bakiye yükleme, transfer, aynı anahtarla tekrar, telafi, mutabakat ve çıkış akışı doğrulandı. Runtime rolünün trigger değiştirme, finansal geçmişi update/truncate etme ve kullanıcı rolünü değiştirme izinlerinin bulunmadığı SQL ile kontrol edildi. Migration kapalıyken eksik şemayla başlatma reddedildi; Ctrl+C sırasında bağlantı havuzunun kapandığı görüldü.

Türkçe açıklamalı sürümde Java metin sabitleri, OpenAPI ve bütün SQL migration dosyalarının korunduğu otomatik kontrol edildi. SQL ve OpenAPI dosyaları iki sürümde bayt bayt aynıdır. Maven Wrapper sürümü ve dağıtım SHA-256 kontrolü çalıştırıldı. Compose yapılandırması ve PowerShell betiği sözdizimi denetlendi.

Docker motoru çalışmadığı için container build/up yolu çalıştırılmadı. GitHub Actions dosyası hazırlandı; GitHub'a yükleme veya uzaktan CI çalıştırma yapılmadı. Yük/kapasite testi ve gerçek banka entegrasyonu bu test raporunun kapsamında değildir.
