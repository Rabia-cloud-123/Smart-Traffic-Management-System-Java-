// BackgroundPanel.java
import java.awt.*;
import javax.swing.*;

public class BackgroundPanel extends JPanel {
    private Image backgroundImage;

    // Constructor: Image path do
    public BackgroundPanel(String imagePath) {
        try {
            backgroundImage = new ImageIcon(imagePath).getImage();
        } catch (Exception e) {
            System.out.println("Background image not found: " + imagePath);
            backgroundImage = null;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (backgroundImage != null) {
            // Image ko full panel size mein stretch karo
            g.drawImage(backgroundImage, 0, 0, getWidth(), getHeight(), this);
        } else {
            // Agar image nahi mili → fallback color
            g.setColor(new Color(240, 245, 255));
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }
}