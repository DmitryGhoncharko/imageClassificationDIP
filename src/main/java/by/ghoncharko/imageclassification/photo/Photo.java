package by.ghoncharko.imageclassification.photo;

import by.ghoncharko.imageclassification.user.AppUser;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "photos")
public class Photo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String originalFileName;

    @Column(nullable = false, unique = true)
    private String storedFileName;

    @Column(nullable = false)
    private Instant uploadedAt;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private AppUser uploadedBy;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "photo_tags", joinColumns = @JoinColumn(name = "photo_id"))
    @Column(name = "tag", nullable = false, columnDefinition = "TEXT")
    private Set<String> tags = new HashSet<>();

    @Column(name = "clip_embedding_json", columnDefinition = "TEXT")
    private String clipEmbeddingJson;

    public Long getId() {
        return id;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getStoredFileName() {
        return storedFileName;
    }

    public void setStoredFileName(String storedFileName) {
        this.storedFileName = storedFileName;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public AppUser getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(AppUser uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public Set<String> getTags() {
        return tags;
    }

    public void setTags(Set<String> tags) {
        this.tags = tags;
    }

    public String getClipEmbeddingJson() {
        return clipEmbeddingJson;
    }

    public void setClipEmbeddingJson(String clipEmbeddingJson) {
        this.clipEmbeddingJson = clipEmbeddingJson;
    }
}
