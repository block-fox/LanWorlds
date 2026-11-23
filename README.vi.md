# LanWorlds

![LanWorlds](docs/lanworlds-cover.png)

[English](README.md) · [简体中文](README.zh-CN.md) · **Tiếng Việt** · [Русский](README.ru.md) · [Українська](README.uk.md) · [日本語](README.ja.md)

LanWorlds là mod Fabric phía máy khách giúp cải thiện danh sách phòng LAN mặc định của Minecraft. Mod hỗ trợ Minecraft **26.3, 26.2, 1.21.11 và 1.21.1**. Cài Fabric API và tải tệp JAR đúng với phiên bản trò chơi trên [GitHub Releases](https://github.com/block-fox/LanWorlds/releases).

## Tính năng

- Tham gia phòng từ màn hình chơi nhiều người mặc định. Nhấp chuột phải vào phòng để xem biểu tượng, MOTD quảng bá và MOTD máy chủ, địa chỉ, số người chơi và độ trễ nếu có.
- Chặn phòng theo IP, IP và cổng hoặc MOTD quảng bá. Chọn nhiều phòng để chặn cùng lúc. Bạn cũng có thể gỡ nhiều quy tắc từ thẻ phòng bị chặn, ngay cả khi các phòng không trực tuyến.
- Tùy chỉnh truy vấn trạng thái phòng, thời gian chờ, quy tắc chặn mặc định, giao diện và vị trí các nút. Ở chế độ chỉ hiện biểu tượng, bật kéo thả rồi dùng nút chuột trái để đặt biểu tượng. Cài đặt được lưu trong `config/lanworlds.json`.

## Nhóm phòng và tìm kiếm

Mở menu **…** trên màn hình chơi nhiều người để bật nhóm phòng, chọn cách nhóm, tìm kiếm hoặc chọn nhiều mục. Có thể nhóm theo phòng Radmin hay LAN thông thường, IP, cổng hoặc MOTD. Nhấp tiêu đề nhóm để thu gọn hoặc mở rộng. Khi chọn nhiều mục, hộp kiểm của nhóm cho phép chọn hoặc bỏ chọn tất cả các phòng trong nhóm, kể cả khi nhóm đang thu gọn.

Tìm theo IP, cổng, MOTD hoặc tên người dùng Radmin. Gợi ý xuất hiện phía trên hoặc phía dưới ô tìm kiếm, tùy theo hướng mở của menu. Chọn một gợi ý để chuyển đến phòng mà vẫn giữ nguyên danh sách chính.

Giữ Ctrl và nhấp vào phòng để vào chế độ chọn nhiều mục. Nhấp để đổi trạng thái chọn, giữ Shift để chọn một dải hoặc Ctrl+Shift để thêm một dải. Ctrl+A chọn tất cả. Thanh công cụ có thêm các nút chọn tất cả, đảo vùng chọn và xóa vùng chọn. Nhấn Esc lần đầu để bỏ chọn, lần nữa để thoát chế độ. Vùng chọn được khôi phục sau khi làm mới dựa trên địa chỉ và cổng. Không thể tham gia phòng khi đang chọn nhiều mục.

## Tích hợp Radmin tùy chọn

LanWorlds có thể chạy riêng với Fabric API. Khi cài thêm Radmin Linker 1.0.1 cho cùng phiên bản Minecraft, các phòng khớp với IP ảo Radmin sẽ hiện thông tin người dùng, mạng và kết nối. Trang chi tiết phòng cũng có thể mở thuộc tính người dùng và mạng Radmin. Nút khiên xanh dùng cài đặt giao diện và vị trí của LanWorlds. [Radmin Linker](https://github.com/block-fox/radmin-linker)

RadminLAN 0.2.x đã có tính năng phòng LAN cũ nên không tương thích với LanWorlds. Nếu chưa có `config/lanworlds.json`, LanWorlds có thể chuyển cài đặt truy vấn và quy tắc chặn từ `config/radminlan.json`. Tệp gốc được giữ nguyên.

## Phát triển

Minecraft 26.2 và 26.3 dùng chung mã nguồn ở thư mục gốc và cần JDK 25. Các dự án riêng cho 1.21.11 và 1.21.1 dùng JDK 21. Hãy dùng tập lệnh đúng phiên bản để chạy ứng dụng khách phát triển.

```powershell
./scripts/run-26.3.ps1 -JavaHome '<JDK 25 path>' -Online
./scripts/run-26.2.ps1 -JavaHome '<JDK 25 path>' -Online
./scripts/run-1.21.11.ps1 -JavaHome '<JDK 21 path>' -Online
./scripts/run-1.21.1.ps1 -JavaHome '<JDK 21 path>' -Online
```

Dùng `scripts/build-*.ps1` tương ứng để biên dịch. Tệp đầu ra nằm trong `versions/<minecraft-version>/build/`. Hãy thoát ứng dụng khách phát triển trước khi biên dịch hoặc đổi phiên bản. Bỏ `-Online` nếu các thư viện phụ thuộc đã có trong bộ nhớ đệm. Bản sao kho mã thông thường dùng các thư viện Maven công khai.

Kiểm tra hồi quy bao gồm di chuyển cấu hình, tích hợp tùy chọn, chọn phòng, chặn và khôi phục hàng loạt. Với 26.2, truyền thêm các thuộc tính trong `versions/26.2/gradle.properties` dưới dạng đối số `-P`. [Tài liệu tích hợp (tiếng Trung)](docs/radmin-integration.md)

```powershell
./gradlew.bat -I tests/regression.init.gradle lanWorldsRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.11 -I ../../tests/regression.init.gradle lanWorldsRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.1 -I ../../tests/regression.init.gradle lanWorldsRegressionCheck --no-configuration-cache
```

Kiểm tra Mixin tải danh sách chơi nhiều người và các mục LAN trong ứng dụng khách phát triển. Bản 1.21.1 còn kiểm tra nhấp chuột phải để xem chi tiết, chọn nhiều mục, làm mới và chặn hàng loạt. Cấu hình kiểm tra được lưu trong `.tmp/mixin-smoke-runtime/`.

```powershell
./gradlew.bat -I tests/mixin-smoke.init.gradle runClient --no-configuration-cache
./gradlew.bat -p versions/1.21.11 -I ../../tests/mixin-smoke.init.gradle runClient --no-configuration-cache
./gradlew.bat -p versions/1.21.1 -I ../../tests/mixin-smoke.init.gradle runClient --no-configuration-cache
```

## Nguồn gốc và giấy phép

LanWorlds bắt nguồn từ tính năng phòng LAN trong RadminLAN 0.2.0, nay là Radmin Linker. Mod được duy trì độc lập theo giấy phép MIT ban đầu. [Giấy phép](LICENSE)
