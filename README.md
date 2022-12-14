# QuPath_TUNEL

Réécriture du script :

Enlever la duplication des régions DAB et Stroma

Utiliser le StarDist pretrained model H&E pour la détection des nuclei (plutôt que le DSB)

Utiliser la fonction contains() pour la colocalisation des DAB cells avec les nuclei (plutôt qu'une distance entre les deux)
