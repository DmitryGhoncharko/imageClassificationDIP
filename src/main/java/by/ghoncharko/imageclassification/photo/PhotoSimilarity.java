package by.ghoncharko.imageclassification.photo;

public class PhotoSimilarity {
    private final Photo photo;
    private final double score;

    public PhotoSimilarity(Photo photo, double score) {
        this.photo = photo;
        this.score = score;
    }

    public Photo getPhoto() {
        return photo;
    }

    public double getScore() {
        return score;
    }
}
