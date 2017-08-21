package li.cil.bedrockores.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import joptsimple.internal.Strings;
import li.cil.bedrockores.common.BedrockOres;
import li.cil.bedrockores.common.json.BlockStateAdapter;
import li.cil.bedrockores.common.json.OreAdapter;
import li.cil.bedrockores.common.json.ResourceLocationAdapter;
import li.cil.bedrockores.common.json.Types;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.DimensionType;
import net.minecraftforge.fml.common.Loader;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

public final class VeinConfig {
    @SuppressWarnings({"WeakerAccess", "unused"})
    public static final class Ore {
        public String[] comment;
        public boolean enabled = true;

        public IBlockState state;

        public String dimension = "overworld";
        public int weight = 1;

        public int widthMin = 4;
        public int widthMax = 6;

        public int heightMin = 2;
        public int heightMax = 4;

        public int countMin = 8;
        public int countMax = 12;

        public int yieldMin = 2000;
        public int yieldMax = 3000;

        public String group;
        public int groupOrder;
    }

    private static ArrayList<Ore> allOres = new ArrayList<>();
    private static ArrayList<Ore> overworldOres = new ArrayList<>();
    private static ArrayList<Ore> netherOres = new ArrayList<>();
    private static int overworldOreWeightSum;
    private static int netherOreWeightSum;

    @Nullable
    public static Ore getOre(final DimensionType dimensionType, final float r) {
        final ArrayList<Ore> list;
        final int oreWeightSum;
        switch (dimensionType) {
            case OVERWORLD:
                list = overworldOres;
                oreWeightSum = overworldOreWeightSum;
                break;
            case NETHER:
                list = netherOres;
                oreWeightSum = netherOreWeightSum;
                break;
            default:
                return null;
        }

        if (list.isEmpty() || oreWeightSum == 0) {
            return null;
        }

        final int wantWeightSum = (int) (r * oreWeightSum);
        int weightSum = 0;
        for (final Ore ore : list) {
            weightSum += ore.weight;
            if (weightSum > wantWeightSum) {
                return ore;
            }
        }

        return null;
    }

    public static void load() {
        final String configDirectory = Loader.instance().getConfigDir().getPath();

        final Gson gson = new GsonBuilder().
                registerTypeAdapter(ResourceLocation.class, new ResourceLocationAdapter()).
                registerTypeAdapter(IBlockState.class, new BlockStateAdapter()).
                registerTypeAdapter(Ore.class, new OreAdapter()).
                setPrettyPrinting().
                disableHtmlEscaping().
                create();

        loadDefaultOres(gson);
        loadOres(configDirectory, gson);

        // Remove entries where block state could not be loaded ore have no weight.
        allOres.removeIf(ore -> !ore.enabled || ore.weight < 1 || ore.state.getBlock() == Blocks.AIR);

        // Remove grouped entries where a group entry with a lower order exists.
        allOres.removeIf(ore -> {
            if (Strings.isNullOrEmpty(ore.group)) {
                return false;
            }
            for (final Ore otherOre : allOres) {
                if (ore == otherOre) {
                    continue;
                }

                if (!Objects.equals(ore.group, otherOre.group)) {
                    continue;
                }

                if (otherOre.groupOrder <= ore.groupOrder) {
                    return true;
                }
            }
            return false;
        });

        // Order by weight
        allOres.sort(Comparator.comparingInt(a -> a.weight));

        // Build overworld list.
        overworldOres.addAll(allOres);
        overworldOres.removeIf(ore -> !Strings.isNullOrEmpty(ore.dimension) &&
                                      !Objects.equals(ore.dimension, "overworld") &&
                                      !Objects.equals(ore.dimension, "*"));

        overworldOreWeightSum = overworldOres.stream().
                map(ore -> ore.weight).
                reduce((a, b) -> a + b).
                orElse(0);

        // Build nether type list.
        netherOres.addAll(allOres);
        netherOres.removeIf(ore -> !Strings.isNullOrEmpty(ore.dimension) &&
                                   !Objects.equals(ore.dimension, "nether") &&
                                   !Objects.equals(ore.dimension, "*"));

        netherOreWeightSum = netherOres.stream().
                map(ore -> ore.weight).
                reduce((a, b) -> a + b).
                orElse(0);
    }

    private static void loadDefaultOres(final Gson gson) {
        try {
            final ArrayList<Ore> result = loadDefault(Constants.BEDROCK_VEINS_FILENAME, Types.ORE_LIST, gson);
            allOres.clear();
            allOres.addAll(result);
        } catch (final IOException | JsonSyntaxException e) {
            BedrockOres.getLog().warn("Failed reading " + Constants.BEDROCK_VEINS_FILENAME + ".", e);
        }
    }

    private static void loadOres(final String basePath, final Gson gson) {
        final ArrayList<Ore> result = load(allOres, Constants.BEDROCK_VEINS_FILENAME, Types.ORE_LIST, basePath, gson);
        if (result != allOres) {
            allOres.clear();
            allOres.addAll(result);
        }
    }

    private static <T> T load(T value, final String fileName, final Type type, final String basePath, final Gson gson) {
        final File path = Paths.get(basePath, Constants.MOD_ID, fileName).toFile();
        try {
            if (path.exists()) {
                value = load(path, type, gson);
            } else {
                value = loadDefault(fileName, type, gson);
            }
            save(value, path, gson);
        } catch (final IOException | JsonSyntaxException e) {
            BedrockOres.getLog().warn("Failed reading " + fileName + ".", e);
        }
        return value;
    }

    private static <T> T load(final File path, final Type type, final Gson gson) throws IOException, JsonSyntaxException {
        try (final InputStream stream = new FileInputStream(path)) {
            return gson.fromJson(new InputStreamReader(stream), type);
        }
    }

    private static <T> T loadDefault(final String fileName, final Type type, final Gson gson) throws IOException, JsonSyntaxException {
        try (final InputStream stream = Settings.class.getResourceAsStream("/assets/" + Constants.MOD_ID + "/config/" + fileName)) {
            return gson.fromJson(new InputStreamReader(stream), type);
        }
    }

    private static void save(final Object value, final File path, final Gson gson) {
        try {
            FileUtils.writeStringToFile(path, gson.toJson(value));
        } catch (final IOException e) {
            BedrockOres.getLog().warn("Failed writing " + path.toString() + ".", e);
        }
    }

    private VeinConfig() {
    }
}