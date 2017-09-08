package li.cil.bedrockores.common.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import gnu.trove.map.TIntFloatMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntFloatHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import joptsimple.internal.Strings;
import li.cil.bedrockores.common.BedrockOres;
import li.cil.bedrockores.common.json.OreConfigAdapter;
import li.cil.bedrockores.common.json.ResourceLocationAdapter;
import li.cil.bedrockores.common.json.Types;
import li.cil.bedrockores.common.json.WrappedBlockStateAdapter;
import li.cil.bedrockores.util.AlphanumComparator;
import net.minecraft.block.Block;
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
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public enum OreConfigManager {
    INSTANCE;

    // --------------------------------------------------------------------- //

    private static final String ANY_DIMENSION = "*";

    private static final String INDEX_JSON = "_index.json";
    private static final String EXAMPLE_JSON = "_example.json";

    private final ArrayList<OreConfig> allOres = new ArrayList<>();
    private final TIntFloatMap oreExtractionCooldownScale = new TIntFloatHashMap();
    private final Map<DimensionType, List<OreConfig>> oresByDimensionType = new EnumMap<>(DimensionType.class);
    private final TObjectIntMap<DimensionType> oreWeightSumByDimensionType = new TObjectIntHashMap<>(3);
    private boolean shouldReuseOreConfigs;

    // --------------------------------------------------------------------- //

    // !!! BEWARE !!!
    // Dark magic code: we merge deserialized ore configs into the already
    // loaded ores when this is true. This is done by the adapter deserializing
    // the entries accessing the `allOres` list via getOres! This means that a
    // deserialization run can return already known instances. But it's still
    // the simplest way to patch previously loaded entries (i.e. only overwrite
    // fields actually present in the JSON). So yeah. It works.
    // !!! BEWARE !!!

    public boolean shouldReuseOreConfigs() {
        return shouldReuseOreConfigs;
    }

    public List<OreConfig> getOres() {
        return ImmutableList.copyOf(allOres);
    }

    public float getOreExtractionCooldownScale(@Nullable final IBlockState state) {
        if (state != null) {
            final int stateId = Block.getStateId(state);
            if (oreExtractionCooldownScale.containsKey(stateId)) {
                return oreExtractionCooldownScale.get(stateId);
            }
        }
        return 1;
    }

    @Nullable
    public OreConfig getOre(final DimensionType dimensionType, final float r) {
        final List<OreConfig> list = oresByDimensionType.get(dimensionType);
        final int oreWeightSum = oreWeightSumByDimensionType.get(dimensionType);
        if (list == null || list.isEmpty()) {
            return null;
        }

        assert oreWeightSum > 0;

        final int wantWeightSum = (int) (r * oreWeightSum);
        int weightSum = 0;
        for (final OreConfig ore : list) {
            weightSum += ore.weight;
            if (weightSum > wantWeightSum) {
                return ore;
            }
        }

        return null;
    }

    public int getOreTypeCount(final DimensionType dimensionType) {
        final List<OreConfig> list = oresByDimensionType.get(dimensionType);
        if (list == null) {
            return 0; // Won't happen because we call getOre first, but just to be safe.
        }
        return list.size();
    }

    public void load() {
        final String configDirectory = Loader.instance().getConfigDir().getPath();

        final Gson gson = new GsonBuilder().
                registerTypeAdapter(ResourceLocation.class, new ResourceLocationAdapter()).
                registerTypeAdapter(IBlockState.class, new WrappedBlockStateAdapter()).
                registerTypeAdapter(OreConfig.class, new OreConfigAdapter()).
                setPrettyPrinting().
                disableHtmlEscaping().
                create();

        loadBuiltInOres(configDirectory, gson);
        loadUserOres(configDirectory, gson);

        BedrockOres.getLog().info("Done loading ore config, got {} ores. Filtering...", allOres.size());

        // Remove entries where block state could not be loaded.
        allOres.removeIf(ore -> ore.state.getBlockState().getBlock() == Blocks.AIR);

        // Grab extraction speeds for *all* ores we know, even disabled ones, in
        // case of ores left from previous generation (disabled later on in an
        // existing world).
        for (final OreConfig ore : allOres) {
            final int stateId = Block.getStateId(ore.state.getBlockState());
            oreExtractionCooldownScale.put(stateId, Math.max(0, ore.extractionCooldownScale));
        }

        // Remove entries where ores are disabled or have no weight.
        allOres.removeIf(ore -> !ore.enabled || ore.weight < 1);

        BedrockOres.getLog().info("After removing disabled and unavailable ores, got {} ores.", allOres.size());

        // Remove grouped entries where a group entry with a lower order exists.
        for (int i = allOres.size() - 1; i >= 0; i--) {
            final OreConfig ore = allOres.get(i);
            if (Strings.isNullOrEmpty(ore.group)) {
                continue;
            }
            for (int j = 0; j < allOres.size(); j++) {
                if (i == j) {
                    continue;
                }

                final OreConfig otherOre = allOres.get(j);

                if (!Objects.equals(ore.group, otherOre.group)) {
                    continue;
                }

                if (otherOre.groupOrder <= ore.groupOrder) {
                    allOres.remove(i);
                    break;
                }
            }
        }

        BedrockOres.getLog().info("After removing duplicate ores, got {} ores.", allOres.size());

        // Order by weight
        allOres.sort(Comparator.comparingInt(a -> a.weight));

        // Build dimension type specific lists.
        for (final DimensionType dimensionType : DimensionType.values()) {
            final String dimensionName = dimensionType.toString().toLowerCase(Locale.US);
            final List<OreConfig> oresForDimension = new ArrayList<>();
            oresForDimension.addAll(allOres);
            oresForDimension.removeIf(ore -> !Strings.isNullOrEmpty(ore.dimension) && // No filter?
                                             !Objects.equals(ore.dimension.toLowerCase(Locale.US), dimensionName) && // Matches name?
                                             !Objects.equals(ore.dimension, ANY_DIMENSION)); // Matches any?
            oresByDimensionType.put(dimensionType, oresForDimension);
            oreWeightSumByDimensionType.put(dimensionType, oresForDimension.stream().
                    map(ore -> ore.weight).
                    reduce((a, b) -> a + b).
                    orElse(0));
        }

        // We only use weight sorting in per-dimension lists, so now we sort
        // alphabetically for a nicer listing when using the list command.
        allOres.sort(Comparator.comparing(oreConfig -> oreConfig.state.toString()));
    }

    // --------------------------------------------------------------------- //

    private void loadBuiltInOres(final String configDirectory, final Gson gson) {
        // Load index with names of all built-in ore listings.
        final List<String> fileNames;
        try (final InputStream stream = getConfigFileStreamFromJar(INDEX_JSON)) {
            fileNames = gson.fromJson(new InputStreamReader(stream), Types.LIST_STRING);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }

        // Load built-in ore listings.
        for (final String filename : fileNames) {
            try {
                shouldReuseOreConfigs = true;
                final ArrayList<OreConfig> oreList = loadFromJar(filename, Types.LIST_ORE, gson);
                oreList.removeAll(allOres);
                allOres.addAll(oreList);
            } catch (final IOException | JsonSyntaxException e) {
                BedrockOres.getLog().warn("Failed reading '" + filename + "'.", e);
            }
        }

        // Extract example user ore-listing to config dir if it doesn't exist.
        try {
            final Path directory = Paths.get(configDirectory, Constants.MOD_ID);
            directory.toFile().mkdirs();
            final int jsonFileCount = FileUtils.listFiles(directory.toFile(), new String[]{"json"}, false).size();
            if (jsonFileCount == 0) {
                BedrockOres.getLog().info("No JSON config files found, extracting example file.");
                shouldReuseOreConfigs = false;
                final List<OreConfig> oreList = loadFromJar(EXAMPLE_JSON, Types.LIST_ORE, gson);
                final File file = directory.resolve(EXAMPLE_JSON).toFile();
                try {
                    FileUtils.writeStringToFile(file, gson.toJson(oreList), Charset.defaultCharset());
                } catch (final IOException e) {
                    BedrockOres.getLog().warn("Failed writing '" + EXAMPLE_JSON + "'.", e);
                }
            } else {
                BedrockOres.getLog().info("Found JSON config files, skipping extraction of example file.");
            }
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadUserOres(final String configDirectory, final Gson gson) {
        final Collection<File> files = FileUtils.listFiles(Paths.get(configDirectory, Constants.MOD_ID).toFile(), new String[]{"json"}, false);
        files.stream().sorted(Comparator.comparing(File::getName, AlphanumComparator.INSTANCE)).forEach(file -> {
            final List<OreConfig> oreList;
            try (final InputStream stream = new FileInputStream(file)) {
                shouldReuseOreConfigs = true;
                oreList = gson.fromJson(new InputStreamReader(stream), Types.LIST_ORE);
                oreList.removeAll(allOres);
            } catch (final IOException | JsonSyntaxException e) {
                BedrockOres.getLog().warn("Failed reading '" + file.getName() + "'.", e);
                return;
            }

            allOres.addAll(oreList);
        });
    }

    private static <T> T loadFromJar(final String fileName, final Type type, final Gson gson) throws IOException, JsonSyntaxException {
        try (final InputStream stream = getConfigFileStreamFromJar(fileName)) {
            return gson.fromJson(new InputStreamReader(stream), type);
        }
    }

    private static InputStream getConfigFileStreamFromJar(final String fileName) {
        return BedrockOres.class.getResourceAsStream("/assets/" + Constants.MOD_ID + "/config/" + fileName);
    }
}