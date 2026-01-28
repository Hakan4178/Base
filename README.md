Hala geliştiriliyor. Lütfen istediğiniz özellikleri belirtin. 

# Eklenecek özellikler:

-Gyro sensör desteği Yapılıyor buggy eklendi oyunlar tam desteklemiyor düzenleniyor

-Server side ham veri işleme ve ölçeklendirme eklendi(Bazen sapıtıyor) 

-İvvmeölçer desteği için altyapı ve yumuşatma altyapısı deneniyor 

-Tam joystick desteği Xbox ui için compose geçiliyor 

-Ui ve tema düzeltmeleri, özgür ve kişiselleştirilebilir bir tasarım

-Kod daha modüler,test edilebilir ve gnu felsefesine uygun hale gelecek ( Modüler kod fikri ertelendi )


# Linux tarafı için Kurulum

git clone https://github.com/Hakan4178/base.git

cd base

chmod +x install.sh

./install.sh

# Kullanım

./run.sh           # Normal mod

./run.sh -d        # Debug mod (tüm paketleri loglar)

./run.sh -p 5000   # Farklı port

# Android client tarafı için kurulum

Relases kısmındaki en son apk'yı indirip kurun. Aynı ağda otomatik bulur.
